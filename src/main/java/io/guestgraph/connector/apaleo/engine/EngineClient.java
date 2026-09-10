package io.guestgraph.connector.apaleo.engine;

import io.guestgraph.connector.apaleo.engine.model.GuestResolution;
import io.guestgraph.connector.apaleo.engine.model.IngestRecord;
import io.guestgraph.connector.apaleo.engine.model.IngestResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * One connection's calls to the engine (research R7, R8): the source system registered once, a
 * batch of records submitted and every result read, and a guest id resolved. The key is the
 * tenant's agent-registered credential, so everything is attributed to the connector.
 */
public class EngineClient {

  /** The most records one submission carries; a caller never splits one object across two. */
  public static final int BATCH = 100;

  private static final ObjectMapper JSON = new ObjectMapper();

  private final RestClient engine;

  public EngineClient(RestClient engine) {
    this.engine = engine;
  }

  /** Registers the source system; a conflict means it exists, which is the same outcome. */
  public void registerSourceSystem(String code, String name) {
    ResponseEntity<Void> response =
        engine
            .post()
            .uri("/api/v1/source-systems")
            .contentType(MediaType.APPLICATION_JSON)
            .body(JSON.writeValueAsString(Map.of("code", code, "name", name)))
            .retrieve()
            .toBodilessEntity();
    int status = response.getStatusCode().value();
    if (status != HttpStatus.CREATED.value() && status != HttpStatus.CONFLICT.value()) {
      throw new EngineException("register source system", status);
    }
  }

  public List<IngestResult> submit(List<IngestRecord> records) {
    if (records.size() > BATCH) {
      throw new IllegalArgumentException("A batch carries at most " + BATCH + " records");
    }
    ResponseEntity<String> response =
        engine
            .post()
            .uri("/api/v1/records")
            .contentType(MediaType.APPLICATION_JSON)
            .body(JSON.writeValueAsString(records))
            .retrieve()
            .toEntity(String.class);
    if (response.getStatusCode().isError() || response.getBody() == null) {
      throw new EngineException("submit records", response.getStatusCode().value());
    }
    List<IngestResult> results = new ArrayList<>();
    for (Object item : list(parse(response.getBody()).get("results"))) {
      results.add(result((Map<?, ?>) item));
    }
    return results;
  }

  /** Empty when the id never existed in the tenant; the engine's 404. */
  public Optional<GuestResolution> getGuest(UUID guestId) {
    ResponseEntity<String> response =
        engine.get().uri("/api/v1/guests/" + guestId).retrieve().toEntity(String.class);
    if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
      return Optional.empty();
    }
    if (response.getStatusCode().isError() || response.getBody() == null) {
      throw new EngineException("read guest " + guestId, response.getStatusCode().value());
    }
    Map<String, Object> body = parse(response.getBody());
    List<UUID> current = new ArrayList<>();
    for (Object id : list(body.get("currentGuestIds"))) {
      current.add(UUID.fromString(String.valueOf(id)));
    }
    return Optional.of(new GuestResolution(String.valueOf(body.get("status")), current));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(String body) {
    return JSON.readValue(body, Map.class);
  }

  private static IngestResult result(Map<?, ?> item) {
    List<UUID> reviews = new ArrayList<>();
    for (Object id : list(item.get("pendingReviewIds"))) {
      reviews.add(UUID.fromString(String.valueOf(id)));
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> problem =
        item.get("problem") instanceof Map<?, ?> p ? (Map<String, Object>) p : null;
    return new IngestResult(
        text(item.get("externalKey")),
        uuid(item.get("sourceRecordId")),
        uuid(item.get("guestId")),
        text(item.get("status")),
        Boolean.TRUE.equals(item.get("needsReview")),
        reviews,
        problem);
  }

  private static List<?> list(Object value) {
    return value instanceof List<?> l ? l : List.of();
  }

  private static String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static UUID uuid(Object value) {
    return value == null ? null : UUID.fromString(String.valueOf(value));
  }
}
