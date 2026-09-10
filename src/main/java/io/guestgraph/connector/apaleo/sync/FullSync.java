package io.guestgraph.connector.apaleo.sync;

import io.guestgraph.connector.apaleo.apaleo.ApaleoClient;
import io.guestgraph.connector.apaleo.apaleo.ApaleoClients;
import io.guestgraph.connector.apaleo.apaleo.model.Booking;
import io.guestgraph.connector.apaleo.apaleo.model.Page;
import io.guestgraph.connector.apaleo.apaleo.model.Reservation;
import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.persistence.ObjectType;
import io.guestgraph.connector.apaleo.persistence.repo.ConnectionRepo;
import io.guestgraph.connector.apaleo.persistence.repo.SyncPointRepo;
import io.guestgraph.connector.apaleo.persistence.repo.SyncRunRepo;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every reservation of the connection's properties, all statuses, then every booking (research R6):
 * each through the submitter, each reservation's booking fetched when no version of it is known,
 * the sync point advanced per property as data-model rule 4 says. Runs on request, on first start
 * when no sync point exists, and after a gap longer than Apaleo's retry window.
 */
@Service
public class FullSync {

  static final String KIND = "FULL";
  private static final Logger log = LoggerFactory.getLogger(FullSync.class);

  private final ApaleoClients apaleo;
  private final ObjectSubmitter submitter;
  private final SyncPointRepo syncPoints;
  private final SyncRunRepo syncRuns;
  private final ConnectionRepo connections;
  private final TransactionTemplate transactions;
  private final TaskExecutor executor;
  private final Clock clock;

  public FullSync(
      ApaleoClients apaleo,
      ObjectSubmitter submitter,
      SyncPointRepo syncPoints,
      SyncRunRepo syncRuns,
      ConnectionRepo connections,
      TransactionTemplate transactions,
      TaskExecutor executor,
      Clock clock) {
    this.apaleo = apaleo;
    this.submitter = submitter;
    this.syncPoints = syncPoints;
    this.syncRuns = syncRuns;
    this.connections = connections;
    this.transactions = transactions;
    this.executor = executor;
    this.clock = clock;
  }

  /** Starts a run in the background and answers its id; refused while one is in progress. */
  public UUID start(ConnectionConfig c) {
    UUID runId = begin(c);
    executor.execute(() -> execute(c, runId));
    return runId;
  }

  /** Runs to completion on the caller's thread and answers the run id. */
  public UUID run(ConnectionConfig c) {
    UUID runId = begin(c);
    execute(c, runId);
    return runId;
  }

  private UUID begin(ConnectionConfig c) {
    return transactions.execute(
        status -> {
          boolean running =
              syncRuns.findRunning(c.name()).stream().anyMatch(r -> KIND.equals(r.getKind()));
          if (running) {
            throw new RunInProgressException(c.name(), KIND);
          }
          UUID runId = UUID.randomUUID();
          syncRuns.start(c.name(), runId, KIND, clock.instant());
          return runId;
        });
  }

  private void execute(ConnectionConfig c, UUID runId) {
    try {
      ApaleoClient client = apaleo.forConnection(c);
      Set<String> properties = new LinkedHashSet<>();
      for (int page = 1; ; page++) {
        Optional<Page<Reservation>> reservations =
            client.listReservations(c.apaleoPropertyIds(), null, page);
        if (reservations.isEmpty()) {
          break;
        }
        for (Reservation reservation : reservations.get().items()) {
          submitter.submit(c, runId, reservation);
          if (reservation.bookingId() != null
              && !submitter.known(c, ObjectType.BOOKING, reservation.bookingId())) {
            submitter.submit(c, runId, client.getBooking(reservation.bookingId()));
          }
          if (reservation.propertyId() != null) {
            properties.add(reservation.propertyId());
            transactions.executeWithoutResult(
                status ->
                    syncPoints.advance(
                        c.name(),
                        reservation.propertyId(),
                        ObjectSubmitter.instant(reservation.modified())));
          }
        }
      }
      for (int page = 1; ; page++) {
        Optional<Page<Booking>> bookings = client.listBookings(page);
        if (bookings.isEmpty()) {
          break;
        }
        for (Booking booking : bookings.get().items()) {
          submitter.submit(c, runId, booking);
        }
      }
      transactions.executeWithoutResult(
          status -> {
            for (String property : properties) {
              syncPoints.stampFullSync(c.name(), property, clock.instant());
            }
            connections.touchActivity(c.name(), clock.instant());
            syncRuns.finish(c.name(), runId, clock.instant(), "SUCCEEDED", null);
          });
    } catch (RuntimeException e) {
      // The reason, never a payload: exception messages here carry a status and a path.
      log.error("Full sync {} on connection {} failed: {}", runId, c.name(), e.getMessage());
      transactions.executeWithoutResult(
          status -> syncRuns.finish(c.name(), runId, clock.instant(), "FAILED", e.getMessage()));
    }
  }
}
