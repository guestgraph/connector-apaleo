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
    // Apaleo answers 204 when the account holds none, and 404 has been seen for the same
    // (research R11 item 4). Both are "none", and both are answers.
    if (response.getStatusCode() == HttpStatus.NOT_FOUND
        || response.getStatusCode() == HttpStatus.NO_CONTENT) {
      return List.of();
    }
    // Before the empty-body shortcut, not after it: a 500 carries no body either, and reading
    // that as "the account holds none" made a failed listing indistinguishable from an empty
    // one. The connector would then create a second subscription, and a removal would report
    // nothing to remove while Apaleo still held one (spec 009, FR-002 and FR-011).
    if (response.getStatusCode().isError()) {
      throw new ApaleoException("list subscriptions", response.getStatusCode().value());
    }
    if (response.getBody() == null || response.getBody().isBlank()) {
      return List.of();
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

  /**
   * Deletes one subscription by its id. Apaleo's account-wide client can delete any subscription
   * the account holds, so the caller decides which one is this connection's; {@link #find} is how,
   * and nothing here guesses. A refusal is raised rather than swallowed: an operator must never be
   * told a subscription is gone when Apaleo still holds it (spec 009, FR-002 and FR-011).
   */
  public void delete(String id) {
    ResponseEntity<String> response =
        api.delete()
            .uri(PATH + "/" + id)
            .header("Authorization", "Bearer " + auth.token())
            .retrieve()
            .toEntity(String.class);
    if (response.getStatusCode().isError()) {
      throw new ApaleoException("delete subscription", response.getStatusCode().value());
    }
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
