package io.guestgraph.connector.apaleo.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** The harness itself: the application boots against it, and its stubs tell connections apart. */
class HarnessTest extends ConnectorIntegrationTest {

  @Test
  @DisplayName("the application starts against the harness and its health answers")
  void applicationStarts() {
    ResponseEntity<String> health =
        connector().get().uri("/actuator/health").retrieve().toEntity(String.class);

    assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(health.getBody()).contains("UP");
  }

  @Test
  @DisplayName("a token earned by one connection does not open the other's reservations")
  void apaleoStubsTellConnectionsApart() {
    stubReservation(ALPHA, "XPGMSXGF-1", "reservation-example.json");
    RestClient client =
        RestClient.builder().defaultStatusHandler(status -> true, (r, s) -> {}).build();

    ResponseEntity<String> asAlpha =
        client
            .get()
            .uri(APALEO.baseUrl() + "/booking/v1/reservations/XPGMSXGF-1")
            .header("Authorization", ALPHA.bearer())
            .retrieve()
            .toEntity(String.class);
    ResponseEntity<String> asBeta =
        client
            .get()
            .uri(APALEO.baseUrl() + "/booking/v1/reservations/XPGMSXGF-1")
            .header("Authorization", BETA.bearer())
            .retrieve()
            .toEntity(String.class);
    ResponseEntity<String> betaToken =
        client
            .post()
            .uri(APALEO.baseUrl() + "/connect/token")
            .header("Authorization", BETA.basicCredential())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("grant_type=client_credentials")
            .retrieve()
            .toEntity(String.class);

    assertThat(asAlpha.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(asAlpha.getBody()).contains("\"additionalGuests\"");
    assertThat(asBeta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(betaToken.getBody()).contains(BETA.token()).doesNotContain(ALPHA.token());
  }

  @Test
  @DisplayName("the engine stub answers one result per submitted record, under the right key")
  void engineStubAnswersPerRecord() {
    answerIngest("K-2", "DUPLICATE_IGNORED");
    RestClient client =
        RestClient.builder().defaultStatusHandler(status -> true, (r, s) -> {}).build();
    String batch =
        "{\"records\":[{\"externalKey\":\"K-1\"},{\"externalKey\":\"K-2\"},{\"externalKey\":\"K-3\"}]}";

    ResponseEntity<String> asAlpha =
        client
            .post()
            .uri(ENGINE.baseUrl() + "/api/v1/records")
            .header("X-API-Key", ALPHA.engineKey())
            .contentType(MediaType.APPLICATION_JSON)
            .body(batch)
            .retrieve()
            .toEntity(String.class);
    ResponseEntity<String> withWrongKey =
        client
            .post()
            .uri(ENGINE.baseUrl() + "/api/v1/records")
            .header("X-API-Key", "nobody")
            .contentType(MediaType.APPLICATION_JSON)
            .body(batch)
            .retrieve()
            .toEntity(String.class);

    assertThat(asAlpha.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(asAlpha.getBody())
        .contains("\"externalKey\":\"K-1\"")
        .contains("\"externalKey\":\"K-3\"")
        .contains("\"status\":\"DUPLICATE_IGNORED\"")
        .contains("\"status\":\"CREATED_GUEST\"");
    assertThat(withWrongKey.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
