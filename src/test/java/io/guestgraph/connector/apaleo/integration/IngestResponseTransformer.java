package io.guestgraph.connector.apaleo.integration;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The engine's ingest answer, one result per submitted record, so a batch of any size is answered
 * in the shape the real endpoint uses. Guest and record ids are derived from the external key, so a
 * resubmission answers the same guest, as a duplicate would. {@link #override} sets the status of
 * one key, for the duplicate and error cases.
 */
final class IngestResponseTransformer implements ResponseDefinitionTransformerV2 {

  static final String NAME = "ingest-results";
  private static final Map<String, String> OVERRIDES = new ConcurrentHashMap<>();
  private static final ObjectMapper MAPPER = new ObjectMapper();

  static void override(String externalKey, String status) {
    OVERRIDES.put(externalKey, status);
  }

  static void clearOverrides() {
    OVERRIDES.clear();
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public boolean applyGlobally() {
    return false;
  }

  @Override
  public ResponseDefinition transform(ServeEvent serveEvent) {
    JsonNode body = MAPPER.readTree(serveEvent.getRequest().getBodyAsString());
    ArrayNode results = MAPPER.createArrayNode();
    List<JsonNode> records = new ArrayList<>();
    (body.isArray() ? body : body.path("records")).forEach(records::add);
    for (JsonNode record : records) {
      String key = record.path("externalKey").asString();
      String status = OVERRIDES.getOrDefault(key, "CREATED_GUEST");
      ObjectNode result = results.addObject();
      result.put("externalKey", key);
      result.put("status", status);
      result.put("needsReview", false);
      result.putArray("pendingReviewIds");
      if ("ERROR".equals(status)) {
        result.putNull("sourceRecordId");
        result.putNull("guestId");
        result.putObject("problem").put("detail", "stubbed failure for " + key);
      } else {
        result.put(
            "sourceRecordId", UUID.nameUUIDFromBytes(("record:" + key).getBytes()).toString());
        result.put("guestId", UUID.nameUUIDFromBytes(("guest:" + key).getBytes()).toString());
      }
    }
    ObjectNode answer = MAPPER.createObjectNode();
    answer.set("results", results);
    return ResponseDefinitionBuilder.like(serveEvent.getResponseDefinition())
        .but()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(answer.toString())
        .build();
  }
}
