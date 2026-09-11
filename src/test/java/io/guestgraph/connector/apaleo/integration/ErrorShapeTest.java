package io.guestgraph.connector.apaleo.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Spec 008 task T012: every refusal, from every origin, has the family's shape. */
class ErrorShapeTest extends ConnectorIntegrationTest {

  static final String BASE = "https://guestgraph.io/problems/#";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired JdbcClient jdbc;

  @Test
  @DisplayName("the token guard's refusal is a problem with the family's type")
  void filter() {
    assertShape(
        connector().get().uri("/status").retrieve().toEntity(String.class), 401, "unauthorized");
  }

  @Test
  @DisplayName("an unknown connection is a not-found problem")
  void controller() {
    assertShape(
        ops().post().uri("/connections/nope/sync/full").retrieve().toEntity(String.class),
        404,
        "not-found");
  }

  @Test
  @DisplayName("a second run while one runs is a run-in-progress problem")
  void runInProgress() throws InterruptedException {
    jdbc.sql("TRUNCATE object_state, processed_event, sync_point, sync_run, held_guest_id")
        .update();
    stubReservationPages(ALPHA, List.of("reservations-page-1.json"));
    stubBookingPages(ALPHA, List.of("bookings-page-1.json"));
    stubBooking(ALPHA, "XPGMSXGF", "booking-example.json");
    stubBooking(ALPHA, "KLMNPQRS", "booking-distinct-booker.json");
    APALEO.stubFor(
        get(urlPathEqualTo("/booking/v1/reservations"))
            .withHeader("Authorization", equalTo("Bearer " + ALPHA.token()))
            .withQueryParam("pageNumber", equalTo("1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(document("reservations-page-1.json"))
                    .withFixedDelay(1500)));
    ResponseEntity<String> first =
        ops().post().uri("/connections/alpha/sync/full").retrieve().toEntity(String.class);
    assertThat(first.getStatusCode().value()).isEqualTo(202);

    assertShape(
        ops().post().uri("/connections/alpha/sync/full").retrieve().toEntity(String.class),
        409,
        "run-in-progress");

    String runId = JSON.readTree(first.getBody()).get("runId").asString();
    for (int i = 0; i < 100; i++) {
      if (!JSON.readTree(
              ops().get().uri("/connections/alpha/runs/" + runId).retrieve().body(String.class))
          .get("outcome")
          .isNull()) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("the run did not finish");
  }

  @Test
  @DisplayName("a body that is no event is an invalid-request problem")
  void invalidRequest() {
    assertShape(
        connector()
            .post()
            .uri("/apaleo/events/" + ALPHA.webhookSecret())
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"x\":1}")
            .retrieve()
            .toEntity(String.class),
        400,
        "invalid-request");
  }

  @Test
  @DisplayName("what nobody foresaw is an internal-error problem that says nothing of the cause")
  void unforeseen() {
    ResponseEntity<String> r = ops().get().uri("/test/boom").retrieve().toEntity(String.class);
    assertShape(r, 500, "internal-error");
    assertThat(JSON.readTree(r.getBody()).get("detail").asString())
        .isEqualTo("An unexpected error occurred");
    assertThat(r.getBody()).doesNotContain("the cause");
  }

  private RestClient ops() {
    return connector().mutate().defaultHeader("Authorization", "Bearer " + OPS_TOKEN).build();
  }

  private void assertShape(ResponseEntity<String> r, int status, String slug) {
    assertThat(r.getStatusCode().value()).isEqualTo(status);
    assertThat(r.getHeaders().getContentType().toString()).startsWith("application/problem+json");
    JsonNode problem = JSON.readTree(r.getBody());
    assertThat(problem.path("type").asString()).isEqualTo(BASE + slug);
    assertThat(problem.get("title").asString()).isNotBlank();
    assertThat(problem.get("status").asInt()).isEqualTo(status);
    assertThat(problem.get("detail").asString()).isNotBlank();
  }
}
