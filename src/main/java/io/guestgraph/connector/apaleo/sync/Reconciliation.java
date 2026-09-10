package io.guestgraph.connector.apaleo.sync;

import io.guestgraph.connector.apaleo.apaleo.ApaleoClient;
import io.guestgraph.connector.apaleo.apaleo.ApaleoClients;
import io.guestgraph.connector.apaleo.apaleo.model.Page;
import io.guestgraph.connector.apaleo.apaleo.model.Reservation;
import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Connections;
import io.guestgraph.connector.apaleo.config.ConnectorProperties;
import io.guestgraph.connector.apaleo.events.Subscriptions;
import io.guestgraph.connector.apaleo.persistence.ObjectType;
import io.guestgraph.connector.apaleo.persistence.entity.SyncPointEntity;
import io.guestgraph.connector.apaleo.persistence.repo.ConnectionRepo;
import io.guestgraph.connector.apaleo.persistence.repo.SyncPointRepo;
import io.guestgraph.connector.apaleo.persistence.repo.SyncRunRepo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the webhooks miss (research R6): per connection and property, the reservations modified
 * since the sync point minus the overlap, each through the submitter, the booking of each one
 * submitted or not yet handled whole fetched again, the point advanced past whole versions only,
 * and the subscription read once. After a gap longer than Apaleo's retry window a full sync runs in
 * its place.
 */
@Service
public class Reconciliation {

  static final String KIND = "RECONCILE";
  private static final Logger log = LoggerFactory.getLogger(Reconciliation.class);

  private final Connections configured;
  private final ApaleoClients apaleo;
  private final ObjectSubmitter submitter;
  private final SyncPointRepo syncPoints;
  private final SyncRunRepo syncRuns;
  private final ConnectionRepo connections;
  private final Subscriptions subscriptions;
  private final GapGuard gapGuard;
  private final FullSync fullSync;
  private final TransactionTemplate transactions;
  private final Duration overlap;
  private final Clock clock;

  public Reconciliation(
      Connections configured,
      ApaleoClients apaleo,
      ObjectSubmitter submitter,
      SyncPointRepo syncPoints,
      SyncRunRepo syncRuns,
      ConnectionRepo connections,
      Subscriptions subscriptions,
      GapGuard gapGuard,
      FullSync fullSync,
      TransactionTemplate transactions,
      ConnectorProperties properties,
      Clock clock) {
    this.configured = configured;
    this.apaleo = apaleo;
    this.submitter = submitter;
    this.syncPoints = syncPoints;
    this.syncRuns = syncRuns;
    this.connections = connections;
    this.subscriptions = subscriptions;
    this.gapGuard = gapGuard;
    this.fullSync = fullSync;
    this.transactions = transactions;
    this.overlap = properties.reconcile().overlap();
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${connector.reconcile.interval}",
      initialDelayString = "${connector.reconcile.interval}")
  public void runAll() {
    for (ConnectionConfig c : configured.all()) {
      try {
        run(c);
      } catch (RunInProgressException e) {
        log.info("Connection {}: {}", c.name(), e.getMessage());
      } catch (RuntimeException e) {
        log.error("Connection {}: reconciliation failed: {}", c.name(), e.getMessage());
      }
    }
  }

  /**
   * Runs on the caller's thread and answers the run id: a reconciliation, or the full sync that the
   * gap rule asks for instead. Refused while any run is in progress, since both walk the same list.
   */
  public UUID run(ConnectionConfig c) {
    if (gapGuard.gapExceeded(c)) {
      log.warn(
          "Connection {}: no activity for longer than Apaleo's retry window, running a full sync",
          c.name());
      return fullSync.run(c);
    }
    UUID runId = RunStart.begin(c, KIND, connections, syncRuns, transactions, clock);
    execute(c, runId);
    return runId;
  }

  /** The configured properties; with none configured, every property a full sync has seen. */
  private Set<String> properties(ConnectionConfig c) {
    Set<String> properties = new LinkedHashSet<>(c.apaleoPropertyIds());
    if (properties.isEmpty()) {
      List<SyncPointEntity> points = transactions.execute(status -> syncPoints.findAll(c.name()));
      points.forEach(p -> properties.add(p.getKey().propertyId()));
    }
    return properties;
  }

  private void execute(ConnectionConfig c, UUID runId) {
    try {
      ApaleoClient client = apaleo.forConnection(c);
      int errors = 0;
      for (String property : properties(c)) {
        // A property configured since the last full sync has no point yet: it is walked from the
        // start, as the full sync would walk it, and gets its point here.
        Instant through =
            transactions.execute(
                status -> {
                  syncPoints.advance(c.name(), property, Instant.EPOCH);
                  return syncPoints.find(c.name(), property).orElseThrow().getModifiedThrough();
                });
        Instant from = through.equals(Instant.EPOCH) ? null : through.minus(overlap);
        boolean heldBack = false;
        for (int page = 1; ; page++) {
          Optional<Page<Reservation>> reservations =
              client.listReservations(List.of(property), from, page);
          if (reservations.isEmpty()) {
            break;
          }
          for (Reservation reservation : reservations.get().items()) {
            Outcome outcome = submitter.submit(c, runId, reservation);
            Outcome bookingOutcome = Outcome.unchanged();
            Optional<Instant> modified = ObjectSubmitter.parse(reservation.modified());
            // The booking is fetched when the reservation was submitted, when no version of the
            // booking is known, and for every reservation past the point: the point moved only
            // past versions whose booking was handled whole (data-model rule 4), so a booking
            // refused last time is read again though its reservation is unchanged by now.
            boolean pastThePoint = modified.map(at -> at.isAfter(through)).orElse(true);
            if (reservation.bookingId() != null
                && (outcome.kind() == Outcome.Kind.SUBMITTED
                    || pastThePoint
                    || !submitter.known(c, ObjectType.BOOKING, reservation.bookingId()))) {
              bookingOutcome =
                  submitter.submit(c, runId, client.getBooking(reservation.bookingId()));
            }
            errors += outcome.errors() + bookingOutcome.errors();
            if (modified.isEmpty()
                || outcome.kind() == Outcome.Kind.FAILED
                || bookingOutcome.kind() == Outcome.Kind.FAILED) {
              heldBack = true;
            }
            if (!heldBack) {
              transactions.executeWithoutResult(
                  status -> syncPoints.advance(c.name(), property, modified.get()));
            }
          }
        }
        transactions.executeWithoutResult(
            status -> syncPoints.stampReconcile(c.name(), property, clock.instant()));
      }
      subscriptions.check(c);
      // The walk completed, which is the activity the gap rule asks about (FR-012a): a refused
      // record is the engine's answer, retried from the point that did not advance, not a
      // delivery Apaleo gave up on.
      String outcome = errors == 0 ? "SUCCEEDED" : "FAILED";
      String reason = errors == 0 ? null : errors + " records refused by the engine";
      transactions.executeWithoutResult(
          status -> {
            connections.touchActivity(c.name(), clock.instant());
            syncRuns.finish(c.name(), runId, clock.instant(), outcome, reason);
          });
    } catch (RuntimeException e) {
      log.error("Reconciliation {} on connection {} failed: {}", runId, c.name(), e.getMessage());
      transactions.executeWithoutResult(
          status -> syncRuns.finish(c.name(), runId, clock.instant(), "FAILED", e.getMessage()));
    } catch (Error e) {
      transactions.executeWithoutResult(
          status -> syncRuns.finish(c.name(), runId, clock.instant(), "FAILED", e.toString()));
      throw e;
    }
  }
}
