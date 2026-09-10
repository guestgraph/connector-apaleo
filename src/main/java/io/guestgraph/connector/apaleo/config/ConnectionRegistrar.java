package io.guestgraph.connector.apaleo.config;

import io.guestgraph.connector.apaleo.persistence.repo.ConnectionRepo;
import java.time.Clock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes one {@code connection} row per configured connection at start, so every other table has a
 * stable id to reference and the status can list a connection before it has run. Nothing secret is
 * written: the webhook secret goes in as its hash.
 */
@Component
public class ConnectionRegistrar {

  private final Connections connections;
  private final ConnectionRepo repo;
  private final Clock clock;
  private final ObjectMapper mapper;

  public ConnectionRegistrar(
      Connections connections, ConnectionRepo repo, Clock clock, ObjectMapper mapper) {
    this.connections = connections;
    this.repo = repo;
    this.clock = clock;
    this.mapper = mapper;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Order(1)
  @Transactional
  public void register() {
    for (ConnectionConfig c : connections.all()) {
      repo.upsert(
          c.name(),
          c.tenantLabel(),
          c.apaleoAccount(),
          mapper.writeValueAsString(c.apaleoPropertyIds()),
          c.webhookSecretHash(),
          clock.instant());
    }
  }
}
