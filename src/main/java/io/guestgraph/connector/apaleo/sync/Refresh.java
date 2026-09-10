package io.guestgraph.connector.apaleo.sync;

import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Connections;
import io.guestgraph.connector.apaleo.engine.EngineClient;
import io.guestgraph.connector.apaleo.engine.EngineClients;
import io.guestgraph.connector.apaleo.engine.model.GuestResolution;
import io.guestgraph.connector.apaleo.ops.LastErrors;
import io.guestgraph.connector.apaleo.persistence.repo.HeldGuestIdRepo;
import io.guestgraph.connector.apaleo.persistence.repo.SyncRunRepo;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The integrator rule on the held ids (research R8, data-model rule 6, FR-019): every distinct id
 * the connection holds is read from its engine once; MERGED replaces it with the one current id and
 * logs both, SPLIT and RETIRED mark the rows with the current ids and wait for a person, ACTIVE
 * stamps the read. The connector never chooses among several current ids. Nightly by {@code
 * REFRESH_CRON}, and on request; it walks no Apaleo list, so it runs beside a sync.
 */
@Service
public class Refresh {

  static final String KIND = "REFRESH";
  private static final Logger log = LoggerFactory.getLogger(Refresh.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Connections configured;
  private final EngineClients engines;
  private final HeldGuestIdRepo heldGuestIds;
  private final SyncRunRepo syncRuns;
  private final LastErrors lastErrors;
  private final TransactionTemplate transactions;
  private final TaskExecutor executor;
  private final Clock clock;

  public Refresh(
      Connections configured,
      EngineClients engines,
      HeldGuestIdRepo heldGuestIds,
      SyncRunRepo syncRuns,
      LastErrors lastErrors,
      TransactionTemplate transactions,
      @Qualifier("applicationTaskExecutor") TaskExecutor executor,
      Clock clock) {
    this.configured = configured;
    this.engines = engines;
    this.heldGuestIds = heldGuestIds;
    this.syncRuns = syncRuns;
    this.lastErrors = lastErrors;
    this.transactions = transactions;
    this.executor = executor;
    this.clock = clock;
  }

  @Scheduled(cron = "${connector.refresh-cron}")
  public void runAll() {
    for (ConnectionConfig c : configured.all()) {
      try {
        run(c);
      } catch (RuntimeException e) {
        log.error("Connection {}: refresh failed: {}", c.name(), e.getMessage());
      }
    }
  }

  /** Starts in the background and answers the run id. */
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
    UUID runId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status -> syncRuns.start(c.name(), runId, KIND, clock.instant()));
    return runId;
  }

  private void execute(ConnectionConfig c, UUID runId) {
    try {
      EngineClient engine = engines.forConnection(c);
      List<UUID> ids = transactions.execute(status -> heldGuestIds.distinctGuestIds(c.name()));
      int errors = 0;
      for (UUID id : ids) {
        Optional<GuestResolution> answer = engine.getGuest(id);
        if (answer.isEmpty()) {
          // The engine answered with a guest it never had; the row is left as it is, since
          // nothing current can be put in its place, and the run says so.
          log.warn("Connection {}: held guest {} is unknown to the engine", c.name(), id);
          errors++;
          continue;
        }
        GuestResolution resolution = answer.get();
        switch (resolution.status()) {
          case "ACTIVE" -> stamp(c, id, "ACTIVE", null, List.of());
          case "MERGED" -> {
            if (resolution.currentGuestIds().size() != 1) {
              log.warn(
                  "Connection {}: held guest {} is MERGED to {} current ids, left as it is",
                  c.name(),
                  id,
                  resolution.currentGuestIds().size());
              errors++;
              continue;
            }
            UUID survivor = resolution.currentGuestIds().getFirst();
            stamp(c, id, "MERGED", survivor, List.of());
            log.info("Connection {}: held guest {} was merged into {}", c.name(), id, survivor);
          }
          case "SPLIT", "RETIRED" -> {
            stamp(c, id, resolution.status(), null, resolution.currentGuestIds());
            log.warn(
                "Connection {}: held guest {} is {} with {} current ids, awaiting a person",
                c.name(),
                id,
                resolution.status(),
                resolution.currentGuestIds().size());
          }
          default -> {
            log.warn(
                "Connection {}: held guest {} has status {}, left as it is",
                c.name(),
                id,
                resolution.status());
            errors++;
          }
        }
      }
      String outcome = errors == 0 ? "SUCCEEDED" : "FAILED";
      String reason = errors == 0 ? null : errors + " held ids could not be refreshed";
      if (errors > 0) {
        lastErrors.record(c.name(), LastErrors.Where.ENGINE, reason);
      }
      int refused = errors;
      transactions.executeWithoutResult(
          status -> {
            syncRuns.count(c.name(), runId, 0, 0, 0, 0, 0, refused);
            syncRuns.finish(c.name(), runId, clock.instant(), outcome, reason);
          });
    } catch (RuntimeException e) {
      log.error("Refresh {} on connection {} failed: {}", runId, c.name(), e.getMessage());
      lastErrors.record(c.name(), e, LastErrors.Where.ENGINE);
      transactions.executeWithoutResult(
          status -> syncRuns.finish(c.name(), runId, clock.instant(), "FAILED", e.getMessage()));
    } catch (Error e) {
      transactions.executeWithoutResult(
          status -> syncRuns.finish(c.name(), runId, clock.instant(), "FAILED", e.toString()));
      throw e;
    }
  }

  private void stamp(
      ConnectionConfig c, UUID id, String status, UUID replacement, List<UUID> current) {
    String json = JSON.writeValueAsString(current.stream().map(UUID::toString).toList());
    transactions.executeWithoutResult(
        s ->
            heldGuestIds.refresh(
                c.name(),
                id,
                status,
                replacement == null ? null : replacement.toString(),
                json,
                clock.instant()));
  }
}
