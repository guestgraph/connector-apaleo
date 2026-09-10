package io.guestgraph.connector.apaleo.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.guestgraph.connector.apaleo.apaleo.model.Reservation;
import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Connections;
import io.guestgraph.connector.apaleo.persistence.ObjectType;
import io.guestgraph.connector.apaleo.persistence.entity.SyncRunEntity;
import io.guestgraph.connector.apaleo.persistence.repo.HeldGuestIdRepo;
import io.guestgraph.connector.apaleo.persistence.repo.ObjectStateRepo;
import io.guestgraph.connector.apaleo.persistence.repo.SyncPointRepo;
import io.guestgraph.connector.apaleo.persistence.repo.SyncRunRepo;
import io.guestgraph.connector.apaleo.sync.FullSync;
import io.guestgraph.connector.apaleo.sync.ObjectSubmitter;
import io.guestgraph.connector.apaleo.sync.Outcome;
import io.guestgraph.connector.apaleo.sync.RunInProgressException;
import io.guestgraph.connector.apaleo.testing.Recorded;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  @Autowired ObjectSubmitter submitter;
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
    // One object's persons never split across batches; today one object is one batch.
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
      assertThat(batch.getHeader("X-API-Key")).isEqualTo(ALPHA.engineKey());
      assertThat(batch.getBodyAsString()).doesNotContain("\"position\":null");
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
    assertThat(heldGuestIds.distinctGuestIds(ALPHA.name())).hasSize(11);
    assertThat(syncPoints.find(ALPHA.name(), "BER").orElseThrow().getModifiedThrough())
        .isEqualTo(Instant.parse("2026-08-25T17:45:10Z"));
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
    // SUCCEEDED means everything landed; this run says otherwise, and the property's point stays
    // behind the refused version so the reconciliation reads it and everything after it again.
    assertThat(run.getOutcome()).isEqualTo("FAILED");
    assertThat(run.getLastError()).contains("1 records refused");
    assertThat(syncPoints.find(ALPHA.name(), "BER").orElseThrow().getModifiedThrough())
        .isEqualTo(Instant.parse("2026-07-09T14:30:00Z"));
  }

  @Test
  @DisplayName("a run left open by a stopped process is closed at start and blocks nothing")
  void interruptedRunIsRecovered() {
    clean();
    stubAccount(ALPHA);
    ConnectionConfig alpha = connections.byName(ALPHA.name()).orElseThrow();
    jdbc.sql(
            "INSERT INTO sync_run (id, connection_id, kind, started_at)"
                + " VALUES (:id, :c, 'FULL', now())")
        .param("id", UUID.randomUUID())
        .param("c", ALPHA.name())
        .update();

    assertThatThrownBy(() -> fullSync.run(alpha)).isInstanceOf(RunInProgressException.class);

    assertThat(fullSync.recover(alpha)).isEqualTo(1);
    UUID runId = fullSync.run(alpha);
    assertThat(syncRuns.find(ALPHA.name(), runId).orElseThrow().getOutcome())
        .isEqualTo("SUCCEEDED");
  }

  @Test
  @DisplayName("a version whose clock cannot be read is still sent, counted, and not stored")
  @SuppressWarnings("unchecked")
  void unreadableVersionIsSentNotStored() {
    clean();
    ConnectionConfig alpha = connections.byName(ALPHA.name()).orElseThrow();
    Map<String, Object> raw =
        new LinkedHashMap<>(
            JSON.readValue(Recorded.document("reservation-second.json"), Map.class));
    raw.put("modified", "yesterday");

    Outcome outcome = submitter.submit(alpha, null, new Reservation(raw));

    assertThat(outcome.kind()).isEqualTo(Outcome.Kind.FAILED);
    assertThat(outcome.errors()).isEqualTo(1);
    assertThat(ENGINE.findAll(postRequestedFor(urlPathEqualTo("/api/v1/records")))).hasSize(1);
    assertThat(objectStates.find(ALPHA.name(), "reservation", "KLMNPQRS-2")).isEmpty();
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
