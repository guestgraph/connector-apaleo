package io.guestgraph.connector.apaleo.api.events;

import io.guestgraph.connector.apaleo.apaleo.ApaleoClients;
import io.guestgraph.connector.apaleo.apaleo.ApaleoWebhooks;
import io.guestgraph.connector.apaleo.api.ops.LastErrors;
import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Connections;
import io.guestgraph.connector.apaleo.config.ConnectorProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  /**
   * What a connection's subscription is, as last seen or as last asked for (spec 009, data-model).
   * A boolean could not tell a connection deliberately without a subscription from one whose
   * creation failed, and the two want opposite reactions: the first is a deployment in the state
   * its operator chose, the second is a fault worth a warning line.
   */
  public enum State {
    /** Apaleo holds a subscription naming this connection's endpoint. */
    ACTIVE,
    /** Apaleo holds none and nobody asked for that. */
    MISSING,
    /** Apaleo holds none because an operator asked. Lost on restart, by design. */
    REMOVED,
    /** Not read yet, or the last read failed. */
    UNKNOWN
  }

  /** As last seen; {@code reason} carries why it is not active, never a payload. */
  public record Status(
      State state, String id, List<String> eventTypes, Instant checkedAt, String reason) {

    /** Kept because the status document has published it since slice 5. */
    public boolean active() {
      return state == State.ACTIVE;
    }
  }

  private final Connections connections;
  private final ApaleoClients apaleo;
  private final ConnectorProperties properties;
  private final LastErrors lastErrors;
  private final Clock clock;
  private final Map<String, Status> statuses = new ConcurrentHashMap<>();

  public Subscriptions(
      Connections connections,
      ApaleoClients apaleo,
      ConnectorProperties properties,
      LastErrors lastErrors,
      Clock clock) {
    this.connections = connections;
    this.apaleo = apaleo;
    this.properties = properties;
    this.lastErrors = lastErrors;
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
      return record(c, new Status(State.ACTIVE, subscription.id(), events, clock.instant(), null));
    } catch (RuntimeException e) {
      log.error("Connection {}: subscription could not be made: {}", c.name(), e.getMessage());
      lastErrors.record(c.name(), e, LastErrors.Where.APALEO);
      return record(c, new Status(State.MISSING, null, events, clock.instant(), e.getMessage()));
    }
  }

  /** Reads whether the subscription still exists, and records that. */
  public Status check(ConnectionConfig c) {
    List<String> events = properties.apaleo().eventTypes();
    try {
      Optional<ApaleoWebhooks.Subscription> found = apaleo.webhooksFor(c).find(endpointOf(c));
      if (found.isEmpty()) {
        log.warn("Connection {}: no subscription names this connector", c.name());
        return record(
            c, new Status(State.MISSING, null, events, clock.instant(), "no subscription found"));
      }
      return record(c, new Status(State.ACTIVE, found.get().id(), events, clock.instant(), null));
    } catch (RuntimeException e) {
      log.warn("Connection {}: subscription could not be read: {}", c.name(), e.getMessage());
      lastErrors.record(c.name(), e, LastErrors.Where.APALEO);
      return record(c, new Status(State.UNKNOWN, null, events, clock.instant(), e.getMessage()));
    }
  }

  public Status status(ConnectionConfig c) {
    return statuses.getOrDefault(
        c.name(),
        new Status(State.UNKNOWN, null, properties.apaleo().eventTypes(), null, "not checked yet"));
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
