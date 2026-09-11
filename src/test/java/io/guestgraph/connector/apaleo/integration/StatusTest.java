package io.guestgraph.connector.apaleo.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.guestgraph.connector.apaleo.api.events.EventWorker;
import io.guestgraph.connector.apaleo.api.events.Subscriptions;
import io.guestgraph.connector.apaleo.api.ops.LastErrors;
import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Connections;
import io.guestgraph.connector.apaleo.sync.FullSync;
import io.guestgraph.connector.apaleo.testing.Recorded;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Spec 005 task T027: the operations surface, and a log that carries no secret and no person. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatusTest extends ConnectorIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ListAppender<ILoggingEvent> LOG = new ListAppender<>();

  @Autowired FullSync fullSync;
  @Autowired EventWorker worker;
  @Autowired Connections connections;
  @Autowired Subscriptions subscriptions;
  @Autowired LastErrors lastErrors;
  @Autowired JdbcClient jdbc;

  /** Not static: the context, and with it Logback, is up before an instance method runs. */
  @BeforeAll
  void captureLog() {
    LOG.start();
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(LOG);
  }

  /** SC-007: nothing a credential or a person is made of appears in any line of this class. */
  @AfterAll
  void logCarriesNoSecretAndNoPerson() {
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(LOG);
    String log =
        String.join("\n", LOG.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    assertThat(LOG.list).as("the capture saw the tests").isNotEmpty();
    for (Connection c : CONNECTIONS) {
      assertThat(log).doesNotContain(c.clientSecret(), c.engineKey(), c.webhookSecret());
    }
    assertThat(log).doesNotContain(OPS_TOKEN);
    for (String value : personValues()) {
      assertThat(log)
          .as("person value %s", value)
          .doesNotContainPattern("(?<![\\w+])" + Pattern.quote(value) + "(?!\\w)");
    }
  }

  @Test
  @Order(1)
  @DisplayName("the status and the run endpoints need the ops token; health does not")
  void tokenGuardsTheOperations() {
    assertThat(connector().get().uri("/status").retrieve().toBodilessEntity().getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            connector()
                .get()
                .uri("/status")
                .header("Authorization", "Bearer wrong")
                .retrieve()
                .toBodilessEntity()
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            connector()
                .post()
                .uri("/connections/alpha/sync/full")
                .retrieve()
                .toBodilessEntity()
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    // A matrix parameter or an encoded spelling reaches the same handler; the guard holds.
    for (String spelling : List.of("/status;x", "/%73tatus", "/connections/alpha/runs/x;y")) {
      assertThat(connector().get().uri(spelling).retrieve().toBodilessEntity().getStatusCode())
          .as(spelling)
          .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    assertThat(
            connector().get().uri("/actuator/health").retrieve().toBodilessEntity().getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  @Order(2)
  @DisplayName("after a full sync the status shows alpha's run and beta untouched")
  void statusAfterAFullSync() {
    clean();
    stubAccount(ALPHA);
    stubSubscriptions(ALPHA, subscriptionListing(ALPHA));
    subscriptions.check(alpha());
    UUID runId = fullSync.run(alpha());

    JsonNode status = ops("/status");
    JsonNode run = ops("/connections/alpha/runs/" + runId);

    assertThat(status.get("connections")).hasSize(2);
    JsonNode alpha = connection(status, "alpha");
    assertThat(alpha.get("tenantLabel").asString()).isEqualTo("acme");
    assertThat(alpha.get("account").asString()).isEqualTo("ACME");
    assertThat(alpha.get("properties")).map(JsonNode::asString).containsExactly("BER");
    assertThat(alpha.at("/subscription/active").asBoolean()).isTrue();
    assertThat(alpha.at("/subscription/id").asString()).isEqualTo("sub-1");
    assertThat(alpha.at("/subscription/eventTypes")).hasSize(7);
    assertThat(alpha.get("syncPoints")).hasSize(1);
    assertThat(alpha.at("/syncPoints/0/propertyId").asString()).isEqualTo("BER");
    assertThat(alpha.at("/syncPoints/0/modifiedThrough").asString())
        .isEqualTo("2026-08-25T17:45:10Z");
    assertThat(alpha.at("/syncPoints/0/lastFullSyncAt").isNull()).isFalse();
    assertThat(alpha.at("/syncPoints/0/lastReconcileAt").isNull()).isTrue();
    for (String counter :
        List.of(
            "versionsSubmitted", "recordsSubmitted", "duplicates", "flaggedForReview", "errors")) {
      assertThat(alpha.at("/counters/" + counter).asInt())
          .as(counter)
          .isEqualTo(run.get(counter).asInt());
    }
    assertThat(alpha.at("/counters/recordsSubmitted").asInt()).isGreaterThan(0);
    assertThat(alpha.get("pendingEvents").asInt()).isZero();
    assertThat(alpha.get("splitsAwaitingPerson").asInt()).isZero();
    assertThat(alpha.get("lastActivityAt").isNull()).isFalse();
    assertThat(alpha.get("lastRefreshAt").isNull()).isTrue();
    assertThat(alpha.get("lastError").isNull()).isTrue();
    assertThat(run.get("kind").asString()).isEqualTo("FULL");
    assertThat(run.get("outcome").asString()).isEqualTo("SUCCEEDED");
    assertThat(run.get("reservationsSeen").asInt()).isEqualTo(4);
    assertThat(run.get("finishedAt").isNull()).isFalse();

    JsonNode beta = connection(status, "beta");
    assertThat(beta.get("syncPoints")).isEmpty();
    assertThat(beta.at("/counters/recordsSubmitted").asInt()).isZero();
    assertThat(beta.get("lastError").isNull()).isTrue();

    // An event's submission counts on the connection, where no run counts it.
    stubReservationBody(
        ALPHA,
        "XPGMSXGF-1",
        Recorded.document("reservation-example.json")
            .replace("2026-07-09T14:30:00Z", "2026-09-03T09:00:00Z")
            .replace("anna@example.com", "anna.muster@example.com"));
    deliver(Recorded.document("event-reservation-changed.json"));
    assertThat(worker.drain(alpha())).isEqualTo(1);

    JsonNode after = connection(ops("/status"), "alpha");
    assertThat(after.at("/counters/versionsSubmitted").asInt())
        .isEqualTo(run.get("versionsSubmitted").asInt() + 1);
    assertThat(after.at("/counters/recordsSubmitted").asInt())
        .isEqualTo(run.get("recordsSubmitted").asInt() + 2);
  }

  @Test
  @Order(3)
  @DisplayName("a fetch Apaleo refuses shows as the last error, without a secret or a person")
  void lastErrorAfterARefusedFetch() {
    clean();
    stubReservationFailure(ALPHA, "XPGMSXGF-1", 401);
    deliver(Recorded.document("event-reservation-changed.json"));
    worker.drain(alpha());

    JsonNode alpha = connection(ops("/status"), "alpha");

    assertThat(alpha.get("pendingEvents").asInt()).isEqualTo(1);
    assertThat(alpha.at("/lastError/where").asString()).isEqualTo("APALEO");
    assertThat(alpha.at("/lastError/at").isNull()).isFalse();
    String reason = alpha.at("/lastError/reason").asString();
    assertThat(reason).contains("401").doesNotContain(ALPHA.clientSecret(), ALPHA.webhookSecret());
    for (String value : personValues()) {
      assertThat(reason).doesNotContain(value);
    }
    assertThat(connection(ops("/status"), "beta").get("lastError").isNull()).isTrue();
  }

  @Test
  @Order(4)
  @DisplayName(
      "a run started through the endpoint is refused while it runs, then reports its outcome")
  void runsThroughTheEndpoints() throws InterruptedException {
    clean();
    stubAccount(ALPHA);
    // The first page takes a while, so the second request finds the run in progress.
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

    ResponseEntity<String> started = opsPost("/connections/alpha/sync/full");
    ResponseEntity<String> again = opsPost("/connections/alpha/sync/full");
    ResponseEntity<String> reconcile = opsPost("/connections/alpha/sync/reconcile");
    ResponseEntity<String> unknown = opsPost("/connections/nope/sync/full");

    assertThat(started.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    String runId = JSON.readTree(started.getBody()).get("runId").asString();
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(reconcile.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    JsonNode inProgress = ops("/connections/alpha/runs/" + runId);
    assertThat(inProgress.get("outcome").isNull()).isTrue();
    assertThat(inProgress.get("finishedAt").isNull()).isTrue();

    assertThat(awaitOutcome(runId)).isEqualTo("SUCCEEDED");
    assertThat(ops("/connections/alpha/runs/" + runId).get("reservationsSeen").asInt())
        .isEqualTo(4);
    assertThat(
            connector()
                .get()
                .uri("/connections/alpha/runs/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + OPS_TOKEN)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            connector()
                .get()
                .uri("/connections/nope/runs/" + runId)
                .header("Authorization", "Bearer " + OPS_TOKEN)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    // Once finished, a reconciliation may start; it is waited for, so nothing outlives the test.
    ResponseEntity<String> reconciled = opsPost("/connections/alpha/sync/reconcile");
    assertThat(reconciled.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(awaitOutcome(JSON.readTree(reconciled.getBody()).get("runId").asString()))
        .isEqualTo("SUCCEEDED");
  }

  private String awaitOutcome(String runId) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      JsonNode run = ops("/connections/alpha/runs/" + runId);
      if (!run.get("outcome").isNull()) {
        return run.get("outcome").asString();
      }
      Thread.sleep(100);
    }
    throw new AssertionError("run " + runId + " did not finish");
  }

  private void deliver(String body) {
    connector()
        .post()
        .uri("/apaleo/events/" + ALPHA.webhookSecret())
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toBodilessEntity();
  }

  private ConnectionConfig alpha() {
    return connections.byName(ALPHA.name()).orElseThrow();
  }

  private JsonNode ops(String path) {
    String body =
        connector()
            .get()
            .uri(path)
            .header("Authorization", "Bearer " + OPS_TOKEN)
            .retrieve()
            .body(String.class);
    return JSON.readTree(body);
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
    throw new AssertionError("no connection " + id + " in " + status);
  }

  /** Every first name, last name, email and phone the recorded documents carry. */
  private static Set<String> personValues() {
    Pattern field = Pattern.compile("\"(firstName|lastName|email|phone)\": \"([^\"]+)\"");
    Set<String> values = new LinkedHashSet<>();
    try (Stream<Path> files = Files.list(Path.of("src/test/resources/apaleo"))) {
      for (Path file : files.toList()) {
        Matcher m = field.matcher(Files.readString(file));
        while (m.find()) {
          values.add(m.group(2));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    assertThat(values).hasSizeGreaterThan(10);
    return values;
  }

  private void stubAccount(Connection c) {
    stubReservationPages(c, List.of("reservations-page-1.json", "reservations-page-2.json"));
    stubBookingPages(c, List.of("bookings-page-1.json"));
    stubBooking(c, "XPGMSXGF", "booking-example.json");
    stubBooking(c, "KLMNPQRS", "booking-distinct-booker.json");
    stubBooking(c, "QRTLMNOP", "booking-canceled-single.json");
    stubBooking(c, "EMPTYBKG", "booking-no-reservations.json");
  }

  private static String subscriptionListing(Connection c) {
    return """
        [{"id":"sub-1","endpointUrl":"%s","topics":[],"events":["reservation/changed"],"propertyIds":["BER"],"created":"2026-09-01T00:00:00Z"}]
        """
        .formatted(endpointOf(c));
  }

  /** The tables, and the in-memory state an earlier test class may have left. */
  private void clean() {
    jdbc.sql("TRUNCATE object_state, processed_event, sync_point, sync_run, held_guest_id")
        .update();
    // The connection rows stay, since the file is their authority; their counters are the
    // sum of everything before, so they start at zero here.
    jdbc.sql(
            "UPDATE connection SET versions_submitted = 0, records_submitted = 0, duplicates = 0,"
                + " flagged_for_review = 0, errors = 0")
        .update();
    for (Connection c : CONNECTIONS) {
      lastErrors.clear(c.name());
    }
  }
}
