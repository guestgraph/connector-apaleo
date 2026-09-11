package io.guestgraph.connector.apaleo.api.events;

import io.guestgraph.connector.apaleo.apaleo.ApaleoClient;
import io.guestgraph.connector.apaleo.apaleo.ApaleoClients;
import io.guestgraph.connector.apaleo.apaleo.model.Reservation;
import io.guestgraph.connector.apaleo.api.ops.LastErrors;
import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Connections;
import io.guestgraph.connector.apaleo.config.ConnectorProperties;
import io.guestgraph.connector.apaleo.persistence.ObjectType;
import io.guestgraph.connector.apaleo.persistence.entity.ProcessedEventEntity;
import io.guestgraph.connector.apaleo.persistence.repo.ConnectionRepo;
import io.guestgraph.connector.apaleo.persistence.repo.ProcessedEventRepo;
import io.guestgraph.connector.apaleo.sync.ObjectSubmitter;
import io.guestgraph.connector.apaleo.sync.Outcome;
import io.guestgraph.connector.apaleo.sync.RecordsRefusedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes stored deliveries (research R5): connection by connection, in the order received, each
 * fetched with that connection's client and put through the submitter, then marked done. A failure
 * keeps the event pending with its reason and a later attempt, backing off from ten seconds to an
 * hour and never giving up (FR-011); one connection's failures never hold another's queue.
 */
@Service
public class EventWorker {

  static final Duration FIRST_RETRY = Duration.ofSeconds(10);
  static final Duration LONGEST_RETRY = Duration.ofHours(1);
  private static final Logger log = LoggerFactory.getLogger(EventWorker.class);

  private final Connections connections;
  private final ProcessedEventRepo events;
  private final ConnectionRepo connectionRows;
  private final ApaleoClients apaleo;
  private final ObjectSubmitter submitter;
  private final TransactionTemplate transactions;
  private final LastErrors lastErrors;
  private final Duration resyncAfterGap;
  private final Clock clock;

  public EventWorker(
      Connections connections,
      ProcessedEventRepo events,
      ConnectionRepo connectionRows,
      ApaleoClients apaleo,
      ObjectSubmitter submitter,
      TransactionTemplate transactions,
      ConnectorProperties properties,
      LastErrors lastErrors,
      Clock clock) {
    this.connections = connections;
    this.events = events;
    this.connectionRows = connectionRows;
    this.apaleo = apaleo;
    this.submitter = submitter;
    this.transactions = transactions;
    this.resyncAfterGap = properties.resyncAfterGap();
    this.lastErrors = lastErrors;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${connector.events.poll-interval}",
      initialDelayString = "${connector.events.poll-interval}")
  public void drainAll() {
    for (ConnectionConfig c : connections.all()) {
      try {
        drain(c);
      } catch (RuntimeException e) {
        log.error("Connection {}: draining events failed: {}", c.name(), e.getMessage());
      }
    }
  }

  /** Every due event of the connection, once; answers how many were marked done. */
  public int drain(ConnectionConfig c) {
    List<ProcessedEventEntity> due =
        transactions.execute(status -> events.findDue(c.name(), clock.instant()));
    int done = 0;
    for (ProcessedEventEntity event : due) {
      if (process(c, event)) {
        done++;
      }
    }
    return done;
  }

  private boolean process(ConnectionConfig c, ProcessedEventEntity event) {
    String eventId = event.getKey().eventId();
    try {
      int errors = submit(c, event);
      if (errors > 0) {
        throw new RecordsRefusedException(errors);
      }
      transactions.executeWithoutResult(
          status -> {
            events.markDone(c.name(), eventId);
            // Apaleo redelivers a day of events once the endpoint answers again; none of them may
            // close a gap the reconciliation has not yet seen (FR-012a).
            Instant now = clock.instant();
            connectionRows.touchActivityUnlessGap(c.name(), now, now.minus(resyncAfterGap));
          });
      return true;
    } catch (RuntimeException e) {
      // Exception messages here carry a status and a path, never a payload (FR-021).
      Duration wait = backoff(event.getAttempts() + 1);
      log.warn(
          "Connection {}: event {} failed on attempt {}, next in {}: {}",
          c.name(),
          eventId,
          event.getAttempts() + 1,
          wait,
          e.getMessage());
      transactions.executeWithoutResult(
          status ->
              events.markFailed(c.name(), eventId, clock.instant().plus(wait), e.getMessage()));
      // Anything untyped happened while fetching.
      lastErrors.record(c.name(), e, LastErrors.Where.APALEO);
      return false;
    }
  }

  private int submit(ConnectionConfig c, ProcessedEventEntity event) {
    ApaleoClient client = apaleo.forConnection(c);
    String objectId = event.getObjectId();
    if (ObjectType.BOOKING.code().equals(event.getObjectType())) {
      return submitter.submit(c, null, client.getBooking(objectId)).errors();
    }
    if (ObjectType.RESERVATION.code().equals(event.getObjectType())) {
      Reservation reservation = client.getReservation(objectId);
      Outcome outcome = submitter.submit(c, null, reservation);
      int errors = outcome.errors();
      // The booker is on the booking; a reservation seen before its booking brings it along once.
      if (reservation.bookingId() != null
          && !submitter.known(c, ObjectType.BOOKING, reservation.bookingId())) {
        errors += submitter.submit(c, null, client.getBooking(reservation.bookingId())).errors();
      }
      return errors;
    }
    throw new IllegalStateException("no fetch for object type " + event.getObjectType());
  }

  /** Ten seconds, doubled per failed attempt, capped at an hour. */
  static Duration backoff(int attempts) {
    Duration wait = FIRST_RETRY;
    for (int i = 1; i < attempts && wait.compareTo(LONGEST_RETRY) < 0; i++) {
      wait = wait.multipliedBy(2);
    }
    return wait.compareTo(LONGEST_RETRY) > 0 ? LONGEST_RETRY : wait;
  }
}
