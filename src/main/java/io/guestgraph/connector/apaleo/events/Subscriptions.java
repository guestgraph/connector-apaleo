package io.guestgraph.connector.apaleo.events;

import io.guestgraph.connector.apaleo.apaleo.ApaleoClients;
import io.guestgraph.connector.apaleo.apaleo.ApaleoWebhooks;
import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Connections;
import io.guestgraph.connector.apaleo.config.ConnectorProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Each connection's subscription, made to fit at start and confirmed by every reconciliation
 * (research R5, R6). What was last seen is kept in memory for the status: a restart makes the
 * subscription fit again before it reports anything.
 */
@Component
public class Subscriptions {

  private static final Logger log = LoggerFactory.getLogger(Subscriptions.class);

  /** As last seen; {@code reason} carries why it is not active, never a payload. */
  public record Status(boolean active, List<String> eventTypes, Instant checkedAt, String reason) {}

  private final Connections connections;
  private final ApaleoClients apaleo;
  private final ConnectorProperties properties;
  private final Clock clock;
  private final Map<String, Status> statuses = new ConcurrentHashMap<>();

  public Subscriptions(
      Connections connections, ApaleoClients apaleo, ConnectorProperties properties, Clock clock) {
    this.connections = connections;
    this.apaleo = apaleo;
    this.properties = properties;
    this.clock = clock;
  }

  /** After the connection rows and the recovery: the endpoint is listening by now. */
  @EventListener(ApplicationReadyEvent.class)
  @Order(3)
  public void ensureAll() {
    for (ConnectionConfig c : connections.all()) {
      ensure(c);
    }
  }

  /** Creates or replaces the subscription so it names this instance, and records the result. */
  public Status ensure(ConnectionConfig c) {
    List<String> events = properties.apaleo().eventTypes();
    try {
      // Another configured connection on the same Apaleo account keeps its own subscription;
      // anything else under this connector's path is an earlier secret or URL of this one.
      Set<String> others = new HashSet<>();
      for (ConnectionConfig other : connections.all()) {
        if (!other.name().equals(c.name())) {
          others.add(endpointOf(other));
        }
      }
      String prefix = endpointOf(c).substring(0, endpointOf(c).lastIndexOf('/') + 1);
      ApaleoWebhooks.Subscription subscription =
          apaleo
              .webhooksFor(c)
              .ensure(
                  endpointOf(c),
                  events,
                  c.apaleoPropertyIds(),
                  url -> url != null && url.startsWith(prefix) && !others.contains(url));
      log.info("Connection {}: subscription {} in place", c.name(), subscription.id());
      return record(c, new Status(true, events, clock.instant(), null));
    } catch (RuntimeException e) {
      log.error("Connection {}: subscription could not be made: {}", c.name(), e.getMessage());
      return record(c, new Status(false, events, clock.instant(), e.getMessage()));
    }
  }

  /** Reads whether the subscription still exists, and records that. */
  public Status check(ConnectionConfig c) {
    List<String> events = properties.apaleo().eventTypes();
    try {
      boolean exists = apaleo.webhooksFor(c).exists(endpointOf(c));
      if (!exists) {
        log.warn("Connection {}: no subscription names this connector", c.name());
      }
      return record(
          c, new Status(exists, events, clock.instant(), exists ? null : "no subscription found"));
    } catch (RuntimeException e) {
      log.warn("Connection {}: subscription could not be read: {}", c.name(), e.getMessage());
      return record(c, new Status(false, events, clock.instant(), e.getMessage()));
    }
  }

  public Status status(ConnectionConfig c) {
    return statuses.getOrDefault(
        c.name(), new Status(false, properties.apaleo().eventTypes(), null, "not checked yet"));
  }

  /** The URL Apaleo posts to: the public URL, the fixed path and the connection's secret. */
  String endpointOf(ConnectionConfig c) {
    String base = properties.publicUrl();
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base + "/apaleo/events/" + c.webhookSecret();
  }

  private Status record(ConnectionConfig c, Status status) {
    statuses.put(c.name(), status);
    return status;
  }
}
