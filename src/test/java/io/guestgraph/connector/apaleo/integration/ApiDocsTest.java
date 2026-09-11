package io.guestgraph.connector.apaleo.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Spec 007 task T021: the contract the connector serves is the one it vendors. */
class ApiDocsTest extends ConnectorIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  @DisplayName("GET /api-docs answers the vendored contract without the ops token")
  @SuppressWarnings("unchecked")
  void apiDocsServesTheContract() throws IOException {
    ResponseEntity<String> answer =
        connector().get().uri("/api-docs").retrieve().toEntity(String.class);

    assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode served = JSON.readTree(answer.getBody());
    Map<String, Object> contract =
        new Yaml().load(Files.readString(Path.of("api/connector-api.yaml")));
    Map<String, Object> paths = (Map<String, Object>) contract.get("paths");
    assertThat(served.get("paths").propertyNames())
        .containsExactlyInAnyOrderElementsOf(paths.keySet());
    assertThat(served.at("/info/title").asString())
        .isEqualTo(((Map<String, Object>) contract.get("info")).get("title"));
  }
}
