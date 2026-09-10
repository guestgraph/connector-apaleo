package io.guestgraph.connector.apaleo.sync;

import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Connections;
import io.guestgraph.connector.apaleo.config.ConnectorProperties;
import io.guestgraph.connector.apaleo.persistence.repo.SyncPointRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * What every connection needs once the application is up: runs a stopped process left open are
 * closed, and a first full sync starts when no sync point exists yet (spec US1, quickstart step 3).
 * Runs after the connection rows are written.
 */
@Component
public class StartupRuns {

  private static final Logger log = LoggerFactory.getLogger(StartupRuns.class);

  private final Connections connections;
  private final SyncPointRepo syncPoints;
  private final FullSync fullSync;
  private final ConnectorProperties properties;

  public StartupRuns(
      Connections connections,
      SyncPointRepo syncPoints,
      FullSync fullSync,
      ConnectorProperties properties) {
    this.connections = connections;
    this.syncPoints = syncPoints;
    this.fullSync = fullSync;
    this.properties = properties;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Order(2)
  public void onReady() {
    for (ConnectionConfig c : connections.all()) {
      int interrupted = fullSync.recover(c);
      if (interrupted > 0) {
        log.warn("Connection {}: {} runs were interrupted by a restart", c.name(), interrupted);
      }
      if (Boolean.TRUE.equals(properties.syncOnBoot()) && syncPoints.findAll(c.name()).isEmpty()) {
        log.info("Connection {}: no sync point yet, starting a full sync", c.name());
        fullSync.start(c);
      }
    }
  }
}
