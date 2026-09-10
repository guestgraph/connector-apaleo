package io.guestgraph.connector.apaleo.sync;

import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Connections;
import io.guestgraph.connector.apaleo.config.ConnectorProperties;
import io.guestgraph.connector.apaleo.engine.EngineClients;
import io.guestgraph.connector.apaleo.persistence.repo.SyncPointRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * What every connection needs once the application is up: its source system registered at the
 * engine, and a first full sync when no sync point exists yet (spec US1 scenario, quickstart step
 * 3). Runs after the connection rows are written.
 */
@Component
public class StartupRuns {

  private static final Logger log = LoggerFactory.getLogger(StartupRuns.class);

  private final Connections connections;
  private final EngineClients engines;
  private final SyncPointRepo syncPoints;
  private final FullSync fullSync;
  private final ConnectorProperties properties;

  public StartupRuns(
      Connections connections,
      EngineClients engines,
      SyncPointRepo syncPoints,
      FullSync fullSync,
      ConnectorProperties properties) {
    this.connections = connections;
    this.engines = engines;
    this.syncPoints = syncPoints;
    this.fullSync = fullSync;
    this.properties = properties;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Order(2)
  public void onReady() {
    for (ConnectionConfig c : connections.all()) {
      try {
        engines.forConnection(c).registerSourceSystem(properties.engineSourceSystem(), "Apaleo");
      } catch (RuntimeException e) {
        log.error("Connection {}: source system not registered: {}", c.name(), e.getMessage());
      }
      if (Boolean.TRUE.equals(properties.syncOnBoot()) && syncPoints.findAll(c.name()).isEmpty()) {
        log.info("Connection {}: no sync point yet, starting a full sync", c.name());
        fullSync.start(c);
      }
    }
  }
}
