package io.guestgraph.connector.apaleo.sync;

import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.ConnectorProperties;
import io.guestgraph.connector.apaleo.persistence.repo.ConnectionRepo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-012a: Apaleo retries a failed delivery for a day, so a connection with no successful activity
 * for longer than that may have lost booking events, which nothing but a full sync can recover.
 * Activity is a processed event or a finished run, whichever came last.
 */
@Component
public class GapGuard {

  private final ConnectionRepo connections;
  private final Duration resyncAfterGap;
  private final Clock clock;

  public GapGuard(ConnectionRepo connections, ConnectorProperties properties, Clock clock) {
    this.connections = connections;
    this.resyncAfterGap = properties.resyncAfterGap();
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public boolean gapExceeded(ConnectionConfig c) {
    Optional<Instant> lastActivity = connections.find(c.name()).map(row -> row.getLastActivityAt());
    return lastActivity
        .map(at -> Duration.between(at, clock.instant()).compareTo(resyncAfterGap) > 0)
        .orElse(false);
  }
}
