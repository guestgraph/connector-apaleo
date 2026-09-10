package io.guestgraph.connector.apaleo.sync;

import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.persistence.entity.SyncRunEntity;
import io.guestgraph.connector.apaleo.persistence.repo.ConnectionRepo;
import io.guestgraph.connector.apaleo.persistence.repo.SyncRunRepo;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One run at a time on a connection, whatever its kind: the full sync and the reconciliation walk
 * the same list, so the connection row is locked while the open runs are read and the new one is
 * written, and a scheduler tick and a request cannot both pass.
 */
final class RunStart {

  private RunStart() {}

  static UUID begin(
      ConnectionConfig c,
      String kind,
      ConnectionRepo connections,
      SyncRunRepo syncRuns,
      TransactionTemplate transactions,
      Clock clock) {
    return transactions.execute(
        status -> {
          connections.lock(c.name());
          Optional<String> running =
              syncRuns.findRunning(c.name()).stream().map(SyncRunEntity::getKind).findFirst();
          if (running.isPresent()) {
            throw new RunInProgressException(c.name(), running.get());
          }
          UUID runId = UUID.randomUUID();
          syncRuns.start(c.name(), runId, kind, clock.instant());
          return runId;
        });
  }
}
