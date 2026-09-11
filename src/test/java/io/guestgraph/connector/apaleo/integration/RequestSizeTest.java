package io.guestgraph.connector.apaleo.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.connector.apaleo.persistence.repo.ProcessedEventRepo;
import io.guestgraph.connector.apaleo.testing.Recorded;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Spec 007 task T022: a body over the cap is refused before anything reads it. */
class RequestSizeTest extends ConnectorIntegrationTest {

  @Autowired ProcessedEventRepo events;
  @Autowired JdbcClient jdbc;

  @Test
  @DisplayName("a body over the cap answers 413 as a problem detail and stores nothing")
  void oversizedBodyIsRefused() {
    jdbc.sql("TRUNCATE processed_event").update();
    String oversized =
        "{\"id\":\"ev-big\",\"topic\":\"Reservation\",\"pad\":\"" + "x".repeat(5000) + "\"}";

    ResponseEntity<String> answer = deliver(oversized);

    assertThat(answer.getStatusCode().value()).isEqualTo(413);
    assertThat(answer.getHeaders().getContentType().toString())
        .startsWith("application/problem+json");
    assertThat(answer.getBody()).contains("\"status\":413");
    assertThat(events.countPending(ALPHA.name())).isZero();
    assertThat(events.find(ALPHA.name(), "ev-big")).isEmpty();
  }

  @Test
  @DisplayName("a body under the cap is accepted as before")
  void ordinaryBodyIsAccepted() {
    assertThat(deliver(Recorded.document("event-reservation-changed.json")).getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
  }

  private ResponseEntity<String> deliver(String body) {
    return connector()
        .post()
        .uri("/apaleo/events/" + ALPHA.webhookSecret())
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }
}
