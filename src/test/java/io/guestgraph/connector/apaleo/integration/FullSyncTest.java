package io.guestgraph.connector.apaleo.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Connections;
import io.guestgraph.connector.apaleo.persistence.ObjectType;
import io.guestgraph.connector.apaleo.persistence.entity.SyncRunEntity;
import io.guestgraph.connector.apaleo.persistence.repo.HeldGuestIdRepo;
import io.guestgraph.connector.apaleo.persistence.repo.ObjectStateRepo;
import io.guestgraph.connector.apaleo.persistence.repo.SyncPointRepo;
import io.guestgraph.connector.apaleo.persistence.repo.SyncRunRepo;
import io.guestgraph.connector.apaleo.sync.FullSync;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Spec 005 task T015: user story 1 end to end on the harness. */
class FullSyncTest extends ConnectorIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired FullSync fullSync;
  @Autowired Connections connections;
  @Autowired ObjectStateRepo objectStates;
  @Autowired HeldGuestIdRepo heldGuestIds;
  @Autowired SyncPointRepo syncPoints;
  @Autowired SyncRunRepo syncRuns;
  @Autowired JdbcClient jdbc;

  @Test
  @DisplayName("a full sync submits every person of every reservation and booking exactly once")
  void fullSyncSubmitsEverything() {
    clean();
    stubAccount(ALPHA);
    ConnectionConfig alpha = connections.byName(ALPHA.name()).orElseThrow();

    UUID runId = fullSync.run(alpha);

    List<LoggedRequest> batches =
        ENGINE.findAll(postRequestedFor(urlPathEqualTo("/api/v1/records")));
    List<String> keys = batches.stream().flatMap(b -> keysOf(b).stream()).toList();
    assertThat(keys)
        .containsExactlyInAnyOrder(
            "XPGMSXGF-1:primaryGuest:2026-07-09T14:30:00Z",
            "XPGMSXGF-1:additionalGuests[0]:2026-07-09T14:30:00Z",
            "KLMNPQRS-1:primaryGuest:2026-07-10T08:15:00Z",
            "KLMNPQRS-1:additionalGuests[0]:2026-07-10T08:15:00Z",
            "KLMNPQRS-1:additionalGuests[1]:2026-07-10T08:15:00Z",
            "KLMNPQRS-2:primaryGuest:2026-07-10T08:15:00Z",
            "QRTLMNOP-1:primaryGuest:2026-08-25T17:45:10Z",
            "XPGMSXGF:booker:2026-07-09T14:30:00Z",
            "KLMNPQRS:booker:2026-07-10T08:15:00Z",
            "QRTLMNOP:booker:2026-08-20T08:00:00Z",
            "EMPTYBKG:booker:2026-09-01T12:00:00Z");
    // One object's persons never split across batches.
    for (LoggedRequest batch : batches) {
      List<String> objects =
          keysOf(batch).stream().map(k -> k.substring(0, k.indexOf(':'))).distinct().toList();
      for (String object : objects) {
        long elsewhere =
            batches.stream()
                .filter(other -> other != batch)
                .flatMap(other -> keysOf(other).stream())
                .filter(k -> k.startsWith(object + ":"))
                .count();
        assertThat(elsewhere).as("%s in one batch only", object).isZero();
      }
    }
    // Every submitted batch went to alpha's key.
    for (LoggedRequest batch : batches) {
      assertThat(batch.getHeader("X-API-Key")).isEqualTo(ALPHA.engineKey());
    }

    assertThat(objectStates.find(ALPHA.name(), ObjectType.RESERVATION.code(), "KLMNPQRS-1"))
        .isPresent()
        .get()
        .satisfies(
            s -> {
              assertThat(s.getLastModified()).isEqualTo(Instant.parse("2026-07-10T08:15:00Z"));
              assertThat(s.getBookingId()).isEqualTo("KLMNPQRS");
              assertThat(s.getPropertyId()).isEqualTo("BER");
              assertThat(s.getRosterHash()).isNotBlank();
            });
    assertThat(objectStates.find(ALPHA.name(), ObjectType.BOOKING.code(), "EMPTYBKG")).isPresent();
    assertThat(heldGuestIds.findByObject(ALPHA.name(), "reservation", "KLMNPQRS-1")).hasSize(3);
    assertThat(heldGuestIds.findByObject(ALPHA.name(), "booking", "KLMNPQRS")).hasSize(1);
    assertThat(heldGuestIds.distinctGuestIds(ALPHA.name())).hasSize(11);
    assertThat(syncPoints.find(ALPHA.name(), "BER"))
        .isPresent()
        .get()
        .satisfies(
            p -> {
              assertThat(p.getModifiedThrough()).isEqualTo(Instant.parse("2026-08-25T17:45:10Z"));
              assertThat(p.getLastFullSyncAt()).isNotNull();
            });
    SyncRunEntity run = syncRuns.find(ALPHA.name(), runId).orElseThrow();
    assertThat(run.getKind()).isEqualTo("FULL");
    assertThat(run.getOutcome()).isEqualTo("SUCCEEDED");
    assertThat(run.getReservationsSeen()).isEqualTo(4);
    assertThat(run.getVersionsSubmitted()).isEqualTo(8);
    assertThat(run.getRecordsSubmitted()).isEqualTo(11);
    assertThat(run.getErrors()).isZero();
    // Nothing of alpha's landed under beta.
    assertThat(heldGuestIds.distinctGuestIds(BETA.name())).isEmpty();
  }

  @Test
  @DisplayName("a second full sync of an unchanged account submits nothing and moves no state")
  void secondRunSubmitsNothing() {
    clean();
    stubAccount(ALPHA);
    ConnectionConfig alpha = connections.byName(ALPHA.name()).orElseThrow();
    fullSync.run(alpha);
    int before = ENGINE.findAll(postRequestedFor(urlPathEqualTo("/api/v1/records"))).size();
    Instant submittedBefore =
        objectStates
            .find(ALPHA.name(), "reservation", "KLMNPQRS-1")
            .orElseThrow()
            .getLastSubmittedAt();

    UUID second = fullSync.run(alpha);

    assertThat(ENGINE.findAll(postRequestedFor(urlPathEqualTo("/api/v1/records")))).hasSize(before);
    assertThat(
            objectStates
                .find(ALPHA.name(), "reservation", "KLMNPQRS-1")
                .orElseThrow()
                .getLastSubmittedAt())
        .isEqualTo(submittedBefore);
    SyncRunEntity run = syncRuns.find(ALPHA.name(), second).orElseThrow();
    assertThat(run.getVersionsSubmitted()).isZero();
    assertThat(run.getReservationsSeen()).isEqualTo(4);
    assertThat(run.getOutcome()).isEqualTo("SUCCEEDED");
  }

  @Test
  @DisplayName("an engine error on one record leaves that object's state unwritten and counts")
  void engineErrorLeavesStateUnwritten() {
    clean();
    stubAccount(ALPHA);
    answerIngest("KLMNPQRS-1:additionalGuests[1]:2026-07-10T08:15:00Z", "ERROR");
    ConnectionConfig alpha = connections.byName(ALPHA.name()).orElseThrow();

    UUID runId = fullSync.run(alpha);

    assertThat(objectStates.find(ALPHA.name(), "reservation", "KLMNPQRS-1")).isEmpty();
    assertThat(objectStates.find(ALPHA.name(), "reservation", "XPGMSXGF-1")).isPresent();
    SyncRunEntity run = syncRuns.find(ALPHA.name(), runId).orElseThrow();
    assertThat(run.getErrors()).isEqualTo(1);
    assertThat(run.getVersionsSubmitted()).isEqualTo(7);
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

  private static List<String> keysOf(LoggedRequest batch) {
    JsonNode body = JSON.readTree(batch.getBodyAsString());
    List<String> keys = new ArrayList<>();
    body.forEach(record -> keys.add(record.get("externalKey").asString()));
    return keys;
  }
}
