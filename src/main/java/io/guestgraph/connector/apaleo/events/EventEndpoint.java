package io.guestgraph.connector.apaleo.events;

import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Connections;
import io.guestgraph.connector.apaleo.persistence.ObjectType;
import io.guestgraph.connector.apaleo.persistence.repo.ProcessedEventRepo;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Apaleo's deliveries (research R5, contract {@code receiveApaleoEvent}): the secret in the path
 * names the connection, the delivery is stored under it and answered before anything is fetched,
 * and the second delivery of an id is acknowledged without a second row. A property the connection
 * does not serve is stored ignored, so the status can count what arrived and was not wanted.
 */
@RestController
public class EventEndpoint {

  static final String PENDING = "PENDING";
  static final String IGNORED = "IGNORED";
  private static final Logger log = LoggerFactory.getLogger(EventEndpoint.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Connections connections;
  private final ProcessedEventRepo events;
  private final Clock clock;

  public EventEndpoint(Connections connections, ProcessedEventRepo events, Clock clock) {
    this.connections = connections;
    this.events = events;
    this.clock = clock;
  }

  @PostMapping("/apaleo/events/{secret}")
  @Transactional
  public ResponseEntity<?> receive(
      @PathVariable String secret, @RequestBody(required = false) String body) {
    Optional<ConnectionConfig> connection = connections.bySecret(secret);
    if (connection.isEmpty()) {
      // The same answer as an unknown path: a probe learns nothing about which secrets exist.
      return ResponseEntity.notFound().build();
    }
    if (body == null || body.isBlank()) {
      return ResponseEntity.ok().build();
    }
    Optional<Delivery> delivery = Delivery.parse(body);
    if (delivery.isEmpty()) {
      return ResponseEntity.of(
              ProblemDetail.forStatusAndDetail(
                  HttpStatus.BAD_REQUEST,
                  "not an Apaleo event: id, topic and data.entityId are required"))
          .build();
    }
    ConnectionConfig c = connection.get();
    Delivery d = delivery.get();
    // Ignored, not pending: a property the connection does not serve, or a topic the worker has
    // no fetch for, would otherwise be retried for ever.
    boolean served =
        (c.apaleoPropertyIds().isEmpty() || c.apaleoPropertyIds().contains(d.propertyId()))
            && (ObjectType.RESERVATION.code().equals(d.objectType())
                || ObjectType.BOOKING.code().equals(d.objectType()));
    int inserted =
        events.insertIfAbsent(
            c.name(),
            d.id(),
            d.eventType(),
            d.objectType(),
            d.entityId(),
            d.propertyId(),
            clock.instant(),
            served ? PENDING : IGNORED);
    if (inserted == 0) {
      log.debug("Connection {}: event {} delivered again", c.name(), d.id());
    }
    return ResponseEntity.accepted().build();
  }

  /** The fields of a delivery the connector reads; the rest of the payload is not kept. */
  record Delivery(
      String id, String eventType, String objectType, String entityId, String propertyId) {

    @SuppressWarnings("unchecked")
    static Optional<Delivery> parse(String body) {
      Map<String, Object> raw;
      try {
        raw = JSON.readValue(body, Map.class);
      } catch (JacksonException e) {
        return Optional.empty();
      }
      if (raw == null) {
        return Optional.empty();
      }
      String id = text(raw.get("id"));
      String topic = text(raw.get("topic"));
      String type = text(raw.get("type"));
      String propertyId = text(raw.get("propertyId"));
      Object data = raw.get("data");
      String entityId =
          data instanceof Map<?, ?> m ? text(((Map<String, Object>) m).get("entityId")) : null;
      if (id == null || topic == null || entityId == null) {
        return Optional.empty();
      }
      String objectType = topic.toLowerCase(Locale.ROOT);
      String eventType = type == null ? objectType : objectType + "/" + type;
      return Optional.of(
          new Delivery(id, eventType, objectType, entityId, propertyId == null ? "" : propertyId));
    }

    private static String text(Object value) {
      return value == null || value.toString().isBlank() ? null : value.toString();
    }
  }
}
