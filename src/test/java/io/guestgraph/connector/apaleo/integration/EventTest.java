package io.guestgraph.connector.apaleo.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Connections;
import io.guestgraph.connector.apaleo.events.EventWorker;
import io.guestgraph.connector.apaleo.persistence.entity.ProcessedEventEntity;
import io.guestgraph.connector.apaleo.persistence.repo.ObjectStateRepo;
import io.guestgraph.connector.apaleo.persistence.repo.ProcessedEventRepo;
import io.guestgraph.connector.apaleo.testing.Recorded;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Spec 005 task T020: a delivery is stored first and answered, then processed once. */
class EventTest extends ConnectorIntegrationTest {

  @Autowired EventWorker worker;
  @Autowired Connections connections;
  @Autowired ProcessedEventRepo events;
  @Autowired ObjectStateRepo objectStates;
  @Autowired JdbcClient jdbc;

  @Test
  @DisplayName("a delivery answers 202 before any fetch; the worker then fetches and submits once")
  void deliveryIsStoredThenProcessedOnce() {
    clean();
    stubReservation(ALPHA, "XPGMSXGF-1", "reservation-example.json");
    stubBooking(ALPHA, "XPGMSXGF", "booking-example.json");

    ResponseEntity<String> answer =
        deliver(ALPHA.webhookSecret(), Recorded.document("event-reservation-changed.json"));

    assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    APALEO.verify(0, getRequestedFor(urlPathEqualTo("/booking/v1/reservations/XPGMSXGF-1")));
    ProcessedEventEntity stored = event(ALPHA, "5a2d0a9e-8a8b-4d64-9e0b-2f8a3c1d1e01");
    assertThat(stored.getState()).isEqualTo("PENDING");
    assertThat(stored.getObjectType()).isEqualTo("reservation");
    assertThat(stored.getObjectId()).isEqualTo("XPGMSXGF-1");

    worker.drain(alpha());

    APALEO.verify(1, getRequestedFor(urlPathEqualTo("/booking/v1/reservations/XPGMSXGF-1")));
    // Two persons on the reservation, and the booking fetched because no version of it was known.
    assertThat(ENGINE.findAll(postRequestedFor(urlPathEqualTo("/api/v1/records")))).hasSize(2);
    assertThat(event(ALPHA, "5a2d0a9e-8a8b-4d64-9e0b-2f8a3c1d1e01").getState()).isEqualTo("DONE");
    assertThat(objectStates.find(ALPHA.name(), "reservation", "XPGMSXGF-1")).isPresent();
    assertThat(objectStates.find(ALPHA.name(), "booking", "XPGMSXGF")).isPresent();

    // The same delivery again is acknowledged and causes no second fetch.
    assertThat(
            deliver(ALPHA.webhookSecret(), Recorded.document("event-reservation-changed.json"))
                .getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    worker.drain(alpha());
    APALEO.verify(1, getRequestedFor(urlPathEqualTo("/booking/v1/reservations/XPGMSXGF-1")));
  }

  @Test
  @DisplayName("a booking event fetches the booking only and submits the booker only")
  void bookingEventFetchesTheBookingOnly() {
    clean();
    stubBooking(ALPHA, "XPGMSXGF", "booking-example.json");

    deliver(ALPHA.webhookSecret(), Recorded.document("event-booking-changed.json"));
    worker.drain(alpha());

    APALEO.verify(1, getRequestedFor(urlPathEqualTo("/booking/v1/bookings/XPGMSXGF")));
    APALEO.verify(0, getRequestedFor(urlPathEqualTo("/booking/v1/reservations/XPGMSXGF-1")));
    assertThat(ENGINE.findAll(postRequestedFor(urlPathEqualTo("/api/v1/records")))).hasSize(1);
    assertThat(
            ENGINE
                .findAll(postRequestedFor(urlPathEqualTo("/api/v1/records")))
                .getFirst()
                .getBodyAsString())
        .contains("XPGMSXGF:booker:");
  }

  @Test
  @DisplayName(
      "two events for one reservation in reverse order both land; the state keeps the newer")
  void outOfOrderEventsBothLand() {
    clean();
    stubBooking(ALPHA, "XPGMSXGF", "booking-example.json");
    String v1 = Recorded.document("reservation-example.json");
    String v2 =
        v1.replace("2026-07-09T14:30:00Z", "2026-07-11T09:00:00Z")
            .replace("anna@example.com", "anna.muster@example.com");

    stubReservationBody(ALPHA, "XPGMSXGF-1", v2);
    deliver(ALPHA.webhookSecret(), eventFor("reservation", "changed", "XPGMSXGF-1", "ev-newer"));
    worker.drain(alpha());
    stubReservationBody(ALPHA, "XPGMSXGF-1", v1);
    deliver(ALPHA.webhookSecret(), eventFor("reservation", "changed", "XPGMSXGF-1", "ev-older"));
    worker.drain(alpha());

    assertThat(ENGINE.findAll(postRequestedFor(urlPathEqualTo("/api/v1/records")))).hasSize(3);
    assertThat(
            objectStates
                .find(ALPHA.name(), "reservation", "XPGMSXGF-1")
                .orElseThrow()
                .getLastModified())
        .isEqualTo(Instant.parse("2026-07-11T09:00:00Z"));
  }

  @Test
  @DisplayName("an edit that changed no person submits nothing")
  void personNeutralEditSubmitsNothing() {
    clean();
    stubBooking(ALPHA, "XPGMSXGF", "booking-example.json");
    stubReservation(ALPHA, "XPGMSXGF-1", "reservation-example.json");
    deliver(ALPHA.webhookSecret(), eventFor("reservation", "changed", "XPGMSXGF-1", "ev-1"));
    worker.drain(alpha());
    int before = ENGINE.findAll(postRequestedFor(urlPathEqualTo("/api/v1/records"))).size();
    String roomChanged =
        Recorded.document("reservation-example.json")
            .replace("2026-07-09T14:30:00Z", "2026-07-12T10:00:00Z")
            .replace("BER-DBL", "BER-SGL");
    stubReservationBody(ALPHA, "XPGMSXGF-1", roomChanged);

    deliver(ALPHA.webhookSecret(), eventFor("reservation", "amended", "XPGMSXGF-1", "ev-2"));
    worker.drain(alpha());

    assertThat(ENGINE.findAll(postRequestedFor(urlPathEqualTo("/api/v1/records")))).hasSize(before);
    assertThat(event(ALPHA, "ev-2").getState()).isEqualTo("DONE");
  }

  @Test
  @DisplayName("a fetch that fails keeps the event pending with a reason, and it succeeds later")
  void failedFetchIsRetried() {
    clean();
    stubReservationFailure(ALPHA, "XPGMSXGF-1", 500);
    deliver(ALPHA.webhookSecret(), eventFor("reservation", "changed", "XPGMSXGF-1", "ev-fail"));

    worker.drain(alpha());

    ProcessedEventEntity failed = event(ALPHA, "ev-fail");
    assertThat(failed.getState()).isEqualTo("PENDING");
    assertThat(failed.getAttempts()).isEqualTo(1);
    assertThat(failed.getNextAttemptAt()).isAfter(Instant.now());
    assertThat(failed.getLastError()).contains("500").doesNotContain("anna");

    // Not due yet: a drain leaves it alone.
    worker.drain(alpha());
    assertThat(event(ALPHA, "ev-fail").getAttempts()).isEqualTo(1);

    stubReservation(ALPHA, "XPGMSXGF-1", "reservation-example.json");
    stubBooking(ALPHA, "XPGMSXGF", "booking-example.json");
    jdbc.sql("UPDATE processed_event SET next_attempt_at = now() - interval '1 second'").update();
    worker.drain(alpha());

    assertThat(event(ALPHA, "ev-fail").getState()).isEqualTo("DONE");
  }

  @Test
  @DisplayName("an event for a property the connection does not serve is ignored and counted")
  void unconfiguredPropertyIsIgnored() {
    clean();

    deliver(ALPHA.webhookSecret(), eventFor("reservation", "changed", "MUC-1", "ev-muc", "MUC"));
    worker.drain(alpha());

    assertThat(event(ALPHA, "ev-muc").getState()).isEqualTo("IGNORED");
    APALEO.verify(0, getRequestedFor(urlPathEqualTo("/booking/v1/reservations/MUC-1")));
  }

  @Test
  @DisplayName("the reachability check is answered 200 and stored nowhere; a wrong secret is 404")
  void reachabilityAndWrongSecret() {
    clean();
    // Apaleo's check is a system document with no entity (sandbox finding, research R11 item 4);
    // an empty body is answered the same way.
    assertThat(
            deliver(ALPHA.webhookSecret(), Recorded.document("event-reachability.json"))
                .getStatusCode())
        .isEqualTo(HttpStatus.OK);
    assertThat(deliver(ALPHA.webhookSecret(), "").getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(events.countPending(ALPHA.name())).isZero();
    assertThat(events.find(ALPHA.name(), "2cb69fe4-bab4-49d2-b064-48ebd9e554b3")).isEmpty();
    assertThat(
            deliver("nobody", Recorded.document("event-reservation-changed.json")).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("a delivery under beta's secret is beta's, whatever it names")
  void deliveryBelongsToTheSecretsConnection() {
    clean();

    deliver(BETA.webhookSecret(), eventFor("reservation", "changed", "MUC-9", "ev-beta", "MUC"));

    assertThat(event(BETA, "ev-beta").getState()).isEqualTo("PENDING");
    assertThat(events.countPending(ALPHA.name())).isZero();
  }

  private ConnectionConfig alpha() {
    return connections.byName(ALPHA.name()).orElseThrow();
  }

  private ProcessedEventEntity event(Connection c, String eventId) {
    return events.find(c.name(), eventId).orElseThrow();
  }

  private ResponseEntity<String> deliver(String secret, String body) {
    return connector()
        .post()
        .uri("/apaleo/events/" + secret)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  private static String eventFor(String topic, String type, String entityId, String eventId) {
    return eventFor(topic, type, entityId, eventId, "BER");
  }

  private static String eventFor(
      String topic, String type, String entityId, String eventId, String propertyId) {
    return """
        {"topic":"%s","type":"%s","id":"%s","accountId":"ACME","propertyId":"%s",
         "timestamp":"2026-07-09T14:30:07Z","data":{"entityId":"%s"}}
        """
        .formatted(
            topic.substring(0, 1).toUpperCase() + topic.substring(1),
            type,
            eventId,
            propertyId,
            entityId);
  }

  private void clean() {
    jdbc.sql("TRUNCATE object_state, processed_event, sync_point, sync_run, held_guest_id")
        .update();
  }
}
