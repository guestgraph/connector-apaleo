package io.guestgraph.connector.apaleo.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** The harness itself: the application boots against the container and both stubs answer. */
class HarnessTest extends ConnectorIntegrationTest {

  @Test
  @DisplayName("the application starts against the harness and its health answers")
  void applicationStarts() {
    ResponseEntity<String> health =
        connector().get().uri("/actuator/health").retrieve().toEntity(String.class);

    assertThat(health.getStatusCode().value()).isEqualTo(200);
    assertThat(health.getBody()).contains("UP");
  }

  @Test
  @DisplayName("the recorded documents are served by the stubs as Apaleo would serve them")
  void stubsServeTheRecordedDocuments() {
    stubReservation("XPGMSXGF-1", "reservation-three-persons.json");
    stubBooking("XPGMSXGF", "booking-distinct-booker.json");

    String reservation =
        RestClient.create()
            .get()
            .uri(APALEO.baseUrl() + "/booking/v1/reservations/XPGMSXGF-1")
            .retrieve()
            .body(String.class);
    String booking =
        RestClient.create()
            .get()
            .uri(APALEO.baseUrl() + "/booking/v1/bookings/XPGMSXGF")
            .retrieve()
            .body(String.class);

    assertThat(reservation).contains("\"additionalGuests\"").contains("\"registeredCard\"");
    assertThat(booking).contains("\"booker\"").contains("\"reservations\"");
  }
}
