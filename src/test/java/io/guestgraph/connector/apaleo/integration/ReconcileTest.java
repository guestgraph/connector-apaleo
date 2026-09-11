package io.guestgraph.connector.apaleo.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.connector.apaleo.api.events.EventWorker;
import io.guestgraph.connector.apaleo.api.events.Subscriptions;
import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Connections;
import io.guestgraph.connector.apaleo.persistence.entity.SyncRunEntity;
import io.guestgraph.connector.apaleo.persistence.repo.ObjectStateRepo;
import io.guestgraph.connector.apaleo.persistence.repo.SyncPointRepo;
import io.guestgraph.connector.apaleo.persistence.repo.SyncRunRepo;
import io.guestgraph.connector.apaleo.sync.FullSync;
import io.guestgraph.connector.apaleo.sync.Reconciliation;
import io.guestgraph.connector.apaleo.testing.Recorded;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Spec 005 task T021: the reconciliation behind the webhooks, and the gap rule. */
class ReconcileTest extends ConnectorIntegrationTest {

  @Autowired Reconciliation reconciliation;
  @Autowired FullSync fullSync;
  @Autowired Subscriptions subscriptions;
  @Autowired EventWorker worker;
  @Autowired Connections connections;
  @Autowired ObjectStateRepo objectStates;
  @Autowired SyncPointRepo syncPoints;
  @Autowired SyncRunRepo syncRuns;
  @Autowired JdbcClient jdbc;

  @Test
  @DisplayName(
      "a reconciliation lists from the point minus the overlap and submits only what changed")
  void reconciliationSubmitsOnlyChanges() {
    clean();
    stubAccount(ALPHA);
    ConnectionConfig alpha = alpha();
    fullSync.run(alpha);
    int before = ENGINE.findAll(postRequestedFor(urlPathEqualTo("/api/v1/records"))).size();
    // KLMNPQRS-2's guest corrected, and its clock moved: the only change in the list.
    String changed =
        Recorded.document("reservations-page-2.json")
            .replace(
                "\"modified\": \"2026-07-10T08:15:00Z\"", "\"modified\": \"2026-09-01T12:00:00Z\"")
            .replace("eva.keller@example.com", "eva@keller.example");
    stubReservationPagesBody(
        ALPHA, List.of(Recorded.document("reservations-page-1.json"), changed));
    stubSubscriptions(ALPHA, subscriptionListing(ALPHA));

    UUID runId = reconciliation.run(alpha);

    List<String> keys =
        ENGINE.findAll(postRequestedFor(urlPathEqualTo("/api/v1/records"))).stream()
            .skip(before)
            .map(b -> b.getBodyAsString())
            .toList();
    assertThat(keys).hasSize(1);
    assertThat(keys.getFirst()).contains("KLMNPQRS-2:primaryGuest:2026-09-01T12:00:00Z");
    APALEO.verify(
        getRequestedFor(urlPathEqualTo("/booking/v1/reservations"))
            .withQueryParam("dateFilter", equalTo("Modification"))
            .withQueryParam("from", equalTo("2026-08-25T16:45:10Z")));
    // The changed reservation's booking was fetched again; unchanged, it was not resubmitted.
    APALEO.verify(2, getRequestedFor(urlPathEqualTo("/booking/v1/bookings/KLMNPQRS")));
    assertThat(syncPoints.find(ALPHA.name(), "BER").orElseThrow().getModifiedThrough())
        .isEqualTo(Instant.parse("2026-09-01T12:00:00Z"));
    SyncRunEntity run = syncRuns.find(ALPHA.name(), runId).orElseThrow();
    assertThat(run.getKind()).isEqualTo("RECONCILE");
    assertThat(run.getOutcome()).isEqualTo("SUCCEEDED");
    assertThat(subscriptions.status(alpha).active()).isTrue();
  }

  @Test
  @DisplayName("a lost subscription shows in the status after the next reconciliation")
  void lostSubscriptionShows() {
    clean();
    stubAccount(ALPHA);
    ConnectionConfig alpha = alpha();
    fullSync.run(alpha);
    stubSubscriptions(ALPHA, subscriptionListing(ALPHA));
    reconciliation.run(alpha);
    assertThat(subscriptions.status(alpha).active()).isTrue();
    stubSubscriptions(
        ALPHA,
        """
        [{"id":"other","endpointUrl":"https://elsewhere.example/hook","topics":[],"events":["reservation/changed"],"propertyIds":["BER"],"created":"2026-09-01T00:00:00Z"}]
        """);

    reconciliation.run(alpha);

    assertThat(subscriptions.status(alpha).active()).isFalse();
    stubSubscriptionsFailure(ALPHA, 404);
    reconciliation.run(alpha);
    assertThat(subscriptions.status(alpha).active()).isFalse();
  }

  @Test
  @DisplayName("a refused booking holds the point, and the next reconciliation reads it again")
  void refusedBookingIsReadAgain() {
    clean();
    stubAccount(ALPHA);
    ConnectionConfig alpha = alpha();
    fullSync.run(alpha);
    // The guest and the booker both corrected; the engine refuses the booker's record.
    String reservationChanged =
        Recorded.document("reservations-page-2.json")
            .replace(
                "\"modified\": \"2026-07-10T08:15:00Z\"", "\"modified\": \"2026-09-01T12:00:00Z\"")
            .replace("eva.keller@example.com", "eva@keller.example");
    stubReservationPagesBody(
        ALPHA, List.of(Recorded.document("reservations-page-1.json"), reservationChanged));
    stubBookingBody(
        ALPHA,
        "KLMNPQRS",
        Recorded.document("booking-distinct-booker.json")
            .replace(
                "\"modified\": \"2026-07-10T08:15:00Z\"", "\"modified\": \"2026-09-01T12:05:00Z\"")
            .replace("daniel.weber@travel-agency.example", "daniel@weber.example"));
    answerIngest("KLMNPQRS:booker:2026-09-01T12:05:00Z", "ERROR");

    UUID first = reconciliation.run(alpha);

    assertThat(syncRuns.find(ALPHA.name(), first).orElseThrow().getOutcome()).isEqualTo("FAILED");
    assertThat(syncPoints.find(ALPHA.name(), "BER").orElseThrow().getModifiedThrough())
        .as("the point stays before the refused version")
        .isEqualTo(Instant.parse("2026-08-25T17:45:10Z"));

    IngestResponseTransformer.clearOverrides();
    UUID second = reconciliation.run(alpha);

    assertThat(syncRuns.find(ALPHA.name(), second).orElseThrow().getOutcome())
        .isEqualTo("SUCCEEDED");
    List<String> bookerSubmissions =
        ENGINE.findAll(postRequestedFor(urlPathEqualTo("/api/v1/records"))).stream()
            .map(b -> b.getBodyAsString())
            .filter(b -> b.contains("KLMNPQRS:booker:2026-09-01T12:05:00Z"))
            .toList();
    assertThat(bookerSubmissions).as("refused once, accepted once").hasSize(2);
    assertThat(syncPoints.find(ALPHA.name(), "BER").orElseThrow().getModifiedThrough())
        .isEqualTo(Instant.parse("2026-09-01T12:00:00Z"));
  }

  @Test
  @DisplayName(
      "a gap longer than the retry window starts a full sync that catches a booker-only edit")
  void longGapStartsAFullSync() {
    clean();
    stubAccount(ALPHA);
    ConnectionConfig alpha = alpha();
    fullSync.run(alpha);
    int before = ENGINE.findAll(postRequestedFor(urlPathEqualTo("/api/v1/records"))).size();
    // The booker's email changed on the booking, and no event arrived: the reservation list is
    // unchanged, so a reconciliation would miss it.
    String bookerChanged =
        Recorded.document("bookings-page-1.json")
            .replace("daniel.weber@travel-agency.example", "daniel@weber.example")
            .replace(
                "\"modified\": \"2026-07-10T08:15:00Z\"", "\"modified\": \"2026-09-02T08:00:00Z\"");
    stubBookingPagesBody(ALPHA, List.of(bookerChanged));
    jdbc.sql("UPDATE connection SET last_activity_at = now() - interval '2 days' WHERE id = :c")
        .param("c", ALPHA.name())
        .update();
    // An event Apaleo redelivers once the endpoint answers again does not close the gap.
    stubReservation(ALPHA, "XPGMSXGF-1", "reservation-example.json");
    connector()
        .post()
        .uri("/apaleo/events/" + ALPHA.webhookSecret())
        .contentType(MediaType.APPLICATION_JSON)
        .body(Recorded.document("event-reservation-changed.json"))
        .retrieve()
        .toBodilessEntity();
    assertThat(worker.drain(alpha)).isEqualTo(1);

    UUID runId = reconciliation.run(alpha);

    SyncRunEntity run = syncRuns.find(ALPHA.name(), runId).orElseThrow();
    assertThat(run.getKind()).as("a full sync ran instead").isEqualTo("FULL");
    List<String> bodies =
        ENGINE.findAll(postRequestedFor(urlPathEqualTo("/api/v1/records"))).stream()
            .skip(before)
            .map(b -> b.getBodyAsString())
            .toList();
    assertThat(bodies).anyMatch(b -> b.contains("KLMNPQRS:booker:2026-09-02T08:00:00Z"));
  }

  private ConnectionConfig alpha() {
    return connections.byName(ALPHA.name()).orElseThrow();
  }

  private static String subscriptionListing(Connection c) {
    return """
        [{"id":"sub-1","endpointUrl":"%s","topics":[],"events":["reservation/changed"],"propertyIds":["BER"],"created":"2026-09-01T00:00:00Z"}]
        """
        .formatted(endpointOf(c));
  }

  private void stubAccount(Connection c) {
    stubReservationPages(c, List.of("reservations-page-1.json", "reservations-page-2.json"));
    stubBookingPages(c, List.of("bookings-page-1.json"));
    stubBooking(c, "XPGMSXGF", "booking-example.json");
    stubBooking(c, "KLMNPQRS", "booking-distinct-booker.json");
    stubBooking(c, "QRTLMNOP", "booking-canceled-single.json");
    stubBooking(c, "EMPTYBKG", "booking-no-reservations.json");
  }

  private void clean() {
    jdbc.sql("TRUNCATE object_state, processed_event, sync_point, sync_run, held_guest_id")
        .update();
  }
}
