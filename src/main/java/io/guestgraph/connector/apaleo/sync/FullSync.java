package io.guestgraph.connector.apaleo.sync;

import io.guestgraph.connector.apaleo.apaleo.ApaleoClient;
import io.guestgraph.connector.apaleo.apaleo.ApaleoClients;
import io.guestgraph.connector.apaleo.apaleo.model.Booking;
import io.guestgraph.connector.apaleo.apaleo.model.Page;
import io.guestgraph.connector.apaleo.apaleo.model.Reservation;
import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.ConnectorProperties;
import io.guestgraph.connector.apaleo.engine.EngineClients;
import io.guestgraph.connector.apaleo.ops.LastErrors;
import io.guestgraph.connector.apaleo.persistence.ObjectType;
import io.guestgraph.connector.apaleo.persistence.repo.ConnectionRepo;
import io.guestgraph.connector.apaleo.persistence.repo.SyncPointRepo;
import io.guestgraph.connector.apaleo.persistence.repo.SyncRunRepo;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
  private final EngineClients engines;
  private final String sourceSystem;
  private final ObjectSubmitter submitter;
  private final SyncPointRepo syncPoints;
  private final SyncRunRepo syncRuns;
  private final ConnectionRepo connections;
  private final TransactionTemplate transactions;
  private final TaskExecutor executor;
  private final LastErrors lastErrors;
  private final Clock clock;

  public FullSync(
      ApaleoClients apaleo,
      EngineClients engines,
      ConnectorProperties properties,
      ObjectSubmitter submitter,
      SyncPointRepo syncPoints,
      SyncRunRepo syncRuns,
      ConnectionRepo connections,
      TransactionTemplate transactions,
      @Qualifier("applicationTaskExecutor") TaskExecutor executor,
      LastErrors lastErrors,
      Clock clock) {
    this.apaleo = apaleo;
    this.engines = engines;
    this.sourceSystem = properties.engineSourceSystem();
    this.submitter = submitter;
    this.syncPoints = syncPoints;
    this.syncRuns = syncRuns;
    this.connections = connections;
    this.transactions = transactions;
    this.executor = executor;
    this.lastErrors = lastErrors;
    this.clock = clock;
  }

  /**
   * Closes every run left open by a process that stopped mid-way, so a restart never inherits a run
   * that blocks the next one; called once at start, before anything runs.
   */
  public int recover(ConnectionConfig c) {
    return transactions.execute(
        status ->
            syncRuns.finishInterrupted(
                c.name(), clock.instant(), "interrupted before it finished"));
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
    return RunStart.begin(c, KIND, connections, syncRuns, transactions, clock);
  }

  private void execute(ConnectionConfig c, UUID runId) {
    try {
      // Registered per run: idempotent at the engine, and a run that cannot register cannot
      // submit, so it fails here rather than record by record.
      engines.forConnection(c).registerSourceSystem(sourceSystem, "Apaleo");
      ApaleoClient client = apaleo.forConnection(c);
      Set<String> properties = new LinkedHashSet<>(c.apaleoPropertyIds());
      // A configured property with no reservations still gets its sync point, from the epoch, so
      // the next start does not read "no sync point" as "never synced".
      transactions.executeWithoutResult(
          status -> {
            for (String property : properties) {
              syncPoints.advance(c.name(), property, Instant.EPOCH);
            }
          });
      int errors = 0;
      Set<String> heldBack = new LinkedHashSet<>();
      for (int page = 1; ; page++) {
        Optional<Page<Reservation>> reservations =
            client.listReservations(c.apaleoPropertyIds(), null, page);
        if (reservations.isEmpty()) {
          break;
        }
        for (Reservation reservation : reservations.get().items()) {
          Outcome outcome = submitter.submit(c, runId, reservation);
          Outcome bookingOutcome = Outcome.unchanged();
          if (reservation.bookingId() != null
              && !submitter.known(c, ObjectType.BOOKING, reservation.bookingId())) {
            bookingOutcome = submitter.submit(c, runId, client.getBooking(reservation.bookingId()));
          }
          errors += outcome.errors() + bookingOutcome.errors();
          String property = reservation.propertyId();
          if (property == null) {
            continue;
          }
          properties.add(property);
          // The point advances only past versions that landed whole (data-model rule 4). The
          // list is sorted by update, so from the first refused version on, the property's point
          // stays where it is and the reconciliation reads everything after it again.
          Optional<Instant> modified = ObjectSubmitter.parse(reservation.modified());
          if (modified.isEmpty()
              || outcome.kind() == Outcome.Kind.FAILED
              || bookingOutcome.kind() == Outcome.Kind.FAILED) {
            heldBack.add(property);
          }
          if (!heldBack.contains(property)) {
            transactions.executeWithoutResult(
                status -> syncPoints.advance(c.name(), property, modified.get()));
          }
        }
      }
      for (int page = 1; ; page++) {
        Optional<Page<Booking>> bookings = client.listBookings(page);
        if (bookings.isEmpty()) {
          break;
        }
        for (Booking booking : bookings.get().items()) {
          errors += submitter.submit(c, runId, booking).errors();
        }
      }
      // SUCCEEDED means everything landed; a run with refused records says so and is retried
      // by the reconciliation, which reads from the sync points that did not advance. Either way
      // the walk completed, which is the activity the gap rule asks about: it is about
      // deliveries Apaleo gave up on, not records the engine refused.
      String outcome = errors == 0 ? "SUCCEEDED" : "FAILED";
      String reason = errors == 0 ? null : errors + " records refused by the engine";
      if (errors > 0) {
        lastErrors.record(c.name(), LastErrors.Where.ENGINE, reason);
      }
      transactions.executeWithoutResult(
          status -> {
            for (String property : properties) {
              syncPoints.stampFullSync(c.name(), property, clock.instant());
            }
            connections.touchActivity(c.name(), clock.instant());
            syncRuns.finish(c.name(), runId, clock.instant(), outcome, reason);
          });
    } catch (RuntimeException e) {
      // The reason, never a payload: exception messages here carry a status and a path.
      log.error("Full sync {} on connection {} failed: {}", runId, c.name(), e.getMessage());
      lastErrors.record(c.name(), e, LastErrors.Where.APALEO);
      transactions.executeWithoutResult(
          status -> syncRuns.finish(c.name(), runId, clock.instant(), "FAILED", e.getMessage()));
    } catch (Error e) {
      transactions.executeWithoutResult(
          status -> syncRuns.finish(c.name(), runId, clock.instant(), "FAILED", e.toString()));
      throw e;
    }
  }
}
