package io.guestgraph.connector.apaleo.apaleo;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * One connection's subscription at Apaleo's webhook API (research R5): listed, created when none
 * names the endpoint, replaced when the one that does carries other events or properties, and left
 * alone otherwise, so a restart writes nothing when nothing changed.
 */
public class ApaleoWebhooks {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String PATH = "/v1/subscriptions";

  private final RestClient api;
  private final ApaleoAuth auth;

  public ApaleoWebhooks(RestClient api, ApaleoAuth auth) {
    this.api = api;
    this.auth = auth;
  }

  /** What Apaleo holds for a subscription; unlisted fields are dropped. */
  public record Subscription(
      String id, String endpointUrl, List<String> events, List<String> propertyIds) {

    boolean matches(List<String> wantedEvents, List<String> wantedProperties) {
      return new HashSet<>(events).equals(new HashSet<>(wantedEvents))
          && new HashSet<>(propertyIds).equals(new HashSet<>(wantedProperties));
    }
  }

  /** Every subscription of the account's client; none when Apaleo has nothing to list. */
  @SuppressWarnings("unchecked")
  public List<Subscription> list() {
    ResponseEntity<String> response =
        api.get()
            .uri(PATH)
            .header("Authorization", "Bearer " + auth.token())
            .retrieve()
            .toEntity(String.class);
    if (response.getStatusCode() == HttpStatus.NOT_FOUND
        || response.getStatusCode() == HttpStatus.NO_CONTENT
        || response.getBody() == null
        || response.getBody().isBlank()) {
      return List.of();
    }
    if (response.getStatusCode().isError()) {
      throw new ApaleoException("list subscriptions", response.getStatusCode().value());
    }
    List<Map<String, Object>> raw = JSON.readValue(response.getBody(), List.class);
    return raw.stream().map(ApaleoWebhooks::subscription).toList();
  }

  /**
   * The subscription for the endpoint, created or brought to these events and properties. {@code
   * stale} says whether a listed endpoint is one this connector registered earlier for the same
   * connection — a rotated secret or a moved public URL — so that one is moved to the endpoint
   * rather than left posting to a URL that answers 404.
   */
  public Subscription ensure(
      String endpointUrl, List<String> events, List<String> propertyIds, Predicate<String> stale) {
    List<Subscription> listed = list();
    Optional<Subscription> existing =
        listed.stream()
            .filter(s -> endpointUrl.equals(s.endpointUrl()))
            .findFirst()
            .or(() -> listed.stream().filter(s -> stale.test(s.endpointUrl())).findFirst());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("endpointUrl", endpointUrl);
    body.put("events", events);
    body.put("propertyIds", propertyIds);
    if (existing.isEmpty()) {
      ResponseEntity<String> response =
          api.post()
              .uri(PATH)
              .header("Authorization", "Bearer " + auth.token())
              .contentType(MediaType.APPLICATION_JSON)
              .body(JSON.writeValueAsString(body))
              .retrieve()
              .toEntity(String.class);
      if (response.getStatusCode().isError()) {
        throw new ApaleoException("create subscription", response.getStatusCode().value());
      }
      String id = response.getBody() == null ? null : idOf(response.getBody());
      return new Subscription(id, endpointUrl, events, propertyIds);
    }
    Subscription current = existing.get();
    if (endpointUrl.equals(current.endpointUrl()) && current.matches(events, propertyIds)) {
      return current;
    }
    ResponseEntity<String> response =
        api.put()
            .uri(PATH + "/" + current.id())
            .header("Authorization", "Bearer " + auth.token())
            .contentType(MediaType.APPLICATION_JSON)
            .body(JSON.writeValueAsString(body))
            .retrieve()
            .toEntity(String.class);
    if (response.getStatusCode().isError()) {
      throw new ApaleoException("replace subscription", response.getStatusCode().value());
    }
    return new Subscription(current.id(), endpointUrl, events, propertyIds);
  }

  /** The subscription naming the endpoint; a listing Apaleo cannot give holds none. */
  public Optional<Subscription> find(String endpointUrl) {
    return list().stream().filter(s -> endpointUrl.equals(s.endpointUrl())).findFirst();
  }

  public boolean exists(String endpointUrl) {
    return find(endpointUrl).isPresent();
  }

  @SuppressWarnings("unchecked")
  private static Subscription subscription(Map<String, Object> raw) {
    return new Subscription(
        string(raw.get("id")),
        string(raw.get("endpointUrl")),
        strings((List<Object>) raw.getOrDefault("events", List.of())),
        strings((List<Object>) raw.getOrDefault("propertyIds", List.of())));
  }

  @SuppressWarnings("unchecked")
  private static String idOf(String body) {
    Map<String, Object> raw = JSON.readValue(body, Map.class);
    return string(raw.get("id"));
  }

  private static String string(Object value) {
    return value == null ? null : value.toString();
  }

  private static List<String> strings(List<Object> values) {
    Set<String> out = new HashSet<>();
    for (Object v : values) {
      if (v != null) {
        out.add(v.toString());
      }
    }
    return List.copyOf(out);
  }
}
