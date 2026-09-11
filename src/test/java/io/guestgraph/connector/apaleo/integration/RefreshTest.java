package io.guestgraph.connector.apaleo.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.guestgraph.connector.apaleo.api.events.EventWorker;
import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Connections;
import io.guestgraph.connector.apaleo.persistence.entity.HeldGuestIdEntity;
import io.guestgraph.connector.apaleo.persistence.repo.HeldGuestIdRepo;
import io.guestgraph.connector.apaleo.testing.Recorded;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Spec 005 task T031: the integrator rule on the held ids, and nothing chosen. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefreshTest extends ConnectorIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ListAppender<ILoggingEvent> LOG = new ListAppender<>();

  @Autowired HeldGuestIdRepo heldGuestIds;
  @Autowired TransactionTemplate transactions;
  @Autowired EventWorker worker;
  @Autowired Connections connections;
  @Autowired JdbcClient jdbc;

  @BeforeAll
  void captureLog() {
    LOG.start();
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(LOG);
  }

  @AfterAll
  void releaseLog() {
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(LOG);
  }

  @Test
  @DisplayName(
      "a refresh replaces a merged id, marks a split and a retired one, leaves an active one")
  void refreshAppliesTheIntegratorRule() throws InterruptedException {
    clean();
    UUID active = UUID.randomUUID();
    UUID merged = UUID.randomUUID();
    UUID survivor = UUID.randomUUID();
    UUID split = UUID.randomUUID();
    UUID splitA = UUID.randomUUID();
    UUID splitB = UUID.randomUUID();
    UUID retired = UUID.randomUUID();
    UUID betas = UUID.randomUUID();
    transactions.executeWithoutResult(
        status -> {
          hold(ALPHA, "reservation", "R-1", "PRIMARY_GUEST", 0, active);
          hold(ALPHA, "reservation", "R-1", "ADDITIONAL_GUEST", 1, merged);
          hold(ALPHA, "reservation", "R-2", "PRIMARY_GUEST", 0, split);
          hold(ALPHA, "booking", "B-1", "BOOKER", 0, retired);
          hold(BETA, "reservation", "M-1", "PRIMARY_GUEST", 0, betas);
        });
    stubGuest(ALPHA, active.toString(), guest(active, "ACTIVE", List.of()));
    stubGuest(ALPHA, merged.toString(), guest(merged, "MERGED", List.of(survivor)));
    stubGuest(ALPHA, split.toString(), guest(split, "SPLIT", List.of(splitA, splitB)));
    stubGuest(ALPHA, retired.toString(), guest(retired, "RETIRED", List.of()));
    stubGuest(BETA, betas.toString(), guest(betas, "ACTIVE", List.of()));

    ResponseEntity<String> started = opsPost("/connections/alpha/refresh");

    assertThat(started.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    String runId = JSON.readTree(started.getBody()).get("runId").asString();
    JsonNode run = awaitFinished(runId);
    assertThat(run.get("kind").asString()).isEqualTo("REFRESH");
    assertThat(run.get("outcome").asString()).isEqualTo("SUCCEEDED");

    List<HeldGuestIdEntity> r1 = heldGuestIds.findByObject(ALPHA.name(), "reservation", "R-1");
    HeldGuestIdEntity primary = slot(r1, "PRIMARY_GUEST");
    HeldGuestIdEntity additional = slot(r1, "ADDITIONAL_GUEST");
    assertThat(primary.getGuestId()).isEqualTo(active);
    assertThat(primary.getResolutionStatus()).isEqualTo("ACTIVE");
    assertThat(additional.getGuestId()).as("merged: replaced by the survivor").isEqualTo(survivor);
    assertThat(additional.getResolutionStatus()).isEqualTo("ACTIVE");
    assertThat(additional.getCurrentGuestIds()).isEmpty();
    HeldGuestIdEntity splitRow =
        heldGuestIds.findByObject(ALPHA.name(), "reservation", "R-2").getFirst();
    assertThat(splitRow.getGuestId()).as("split: kept, nothing chosen").isEqualTo(split);
    assertThat(splitRow.getResolutionStatus()).isEqualTo("SPLIT");
    assertThat(splitRow.getCurrentGuestIds()).containsExactlyInAnyOrder(splitA, splitB);
    HeldGuestIdEntity retiredRow =
        heldGuestIds.findByObject(ALPHA.name(), "booking", "B-1").getFirst();
    assertThat(retiredRow.getGuestId()).isEqualTo(retired);
    assertThat(retiredRow.getResolutionStatus()).isEqualTo("RETIRED");
    assertThat(retiredRow.getCurrentGuestIds()).isEmpty();
    assertThat(retiredRow.getRefreshedAt()).isNotNull();

    List<String> lines = LOG.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertThat(lines)
        .as("one line names both ids")
        .anySatisfy(line -> assertThat(line).contains(merged.toString(), survivor.toString()));
    assertThat(String.join("\n", lines)).doesNotContain(active.toString());

    JsonNode alpha = connection(ops("/status"), "alpha");
    assertThat(alpha.get("splitsAwaitingPerson").asInt()).isEqualTo(2);
    assertThat(alpha.get("lastRefreshAt").isNull()).isFalse();

    // Beta's id was not read, and its row was not touched.
    ENGINE.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/guests/" + betas)));
    assertThat(
            heldGuestIds
                .findByObject(BETA.name(), "reservation", "M-1")
                .getFirst()
                .getRefreshedAt())
        .isNull();
    assertThat(opsPost("/connections/nope/refresh").getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("an id the engine does not know is left and counted; the run says so")
  void unknownIdIsLeftAndCounted() throws InterruptedException {
    clean();
    UUID unknown = UUID.randomUUID();
    transactions.executeWithoutResult(
        status -> hold(ALPHA, "reservation", "R-9", "PRIMARY_GUEST", 0, unknown));

    String runId =
        JSON.readTree(opsPost("/connections/alpha/refresh").getBody()).get("runId").asString();
    JsonNode run = awaitFinished(runId);

    assertThat(run.get("outcome").asString()).isEqualTo("FAILED");
    assertThat(run.get("errors").asInt()).isEqualTo(1);
    HeldGuestIdEntity row =
        heldGuestIds.findByObject(ALPHA.name(), "reservation", "R-9").getFirst();
    assertThat(row.getGuestId()).isEqualTo(unknown);
    assertThat(row.getResolutionStatus()).isEqualTo("ACTIVE");
    assertThat(row.getRefreshedAt()).isNull();
  }

  @Test
  @DisplayName("a resubmitted version rewrites the slot's held id from the new result")
  void resubmissionRewritesTheSlot() {
    clean();
    UUID stale = UUID.randomUUID();
    transactions.executeWithoutResult(
        status -> hold(ALPHA, "reservation", "XPGMSXGF-1", "PRIMARY_GUEST", 0, stale));
    stubReservation(ALPHA, "XPGMSXGF-1", "reservation-example.json");
    stubBooking(ALPHA, "XPGMSXGF", "booking-example.json");
    connector()
        .post()
        .uri("/apaleo/events/" + ALPHA.webhookSecret())
        .contentType(MediaType.APPLICATION_JSON)
        .body(Recorded.document("event-reservation-changed.json"))
        .retrieve()
        .toBodilessEntity();

    worker.drain(alpha());

    HeldGuestIdEntity primary =
        heldGuestIds.findByObject(ALPHA.name(), "reservation", "XPGMSXGF-1").stream()
            .filter(h -> h.getKey().role().equals("PRIMARY_GUEST"))
            .findFirst()
            .orElseThrow();
    // The stub answers a guest id derived from the observation key.
    UUID expected =
        UUID.nameUUIDFromBytes(
            "guest:XPGMSXGF-1:primaryGuest:2026-07-09T14:30:00Z".getBytes(StandardCharsets.UTF_8));
    assertThat(primary.getGuestId()).isNotEqualTo(stale).isEqualTo(expected);
    assertThat(primary.getResolutionStatus()).isEqualTo("ACTIVE");
  }

  private static HeldGuestIdEntity slot(List<HeldGuestIdEntity> rows, String role) {
    return rows.stream().filter(h -> h.getKey().role().equals(role)).findFirst().orElseThrow();
  }

  private void hold(Connection c, String type, String id, String role, int position, UUID guest) {
    heldGuestIds.hold(c.name(), type, id, role, position, guest, UUID.randomUUID());
  }

  private static String guest(UUID id, String status, List<UUID> current) {
    return "{\"id\":\"%s\",\"status\":\"%s\",\"currentGuestIds\":[%s],\"hops\":[]}"
        .formatted(
            id, status, String.join(",", current.stream().map(u -> "\"" + u + "\"").toList()));
  }

  private JsonNode awaitFinished(String runId) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      JsonNode run = ops("/connections/alpha/runs/" + runId);
      if (!run.get("outcome").isNull()) {
        return run;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("run " + runId + " did not finish");
  }

  private ConnectionConfig alpha() {
    return connections.byName(ALPHA.name()).orElseThrow();
  }

  private JsonNode ops(String path) {
    return JSON.readTree(
        connector()
            .get()
            .uri(path)
            .header("Authorization", "Bearer " + OPS_TOKEN)
            .retrieve()
            .body(String.class));
  }

  private ResponseEntity<String> opsPost(String path) {
    return connector()
        .post()
        .uri(path)
        .header("Authorization", "Bearer " + OPS_TOKEN)
        .retrieve()
        .toEntity(String.class);
  }

  private static JsonNode connection(JsonNode status, String id) {
    for (JsonNode c : status.get("connections")) {
      if (id.equals(c.get("id").asString())) {
        return c;
      }
    }
    throw new AssertionError("no connection " + id);
  }

  private void clean() {
    jdbc.sql("TRUNCATE object_state, processed_event, sync_point, sync_run, held_guest_id")
        .update();
  }
}
