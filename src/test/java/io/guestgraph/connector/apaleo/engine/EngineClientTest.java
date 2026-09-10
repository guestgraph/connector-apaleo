package io.guestgraph.connector.apaleo.engine;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.engine.model.GuestResolution;
import io.guestgraph.connector.apaleo.engine.model.IngestRecord;
import io.guestgraph.connector.apaleo.engine.model.IngestResult;
import io.guestgraph.connector.apaleo.engine.model.SourceObject;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spec 005 task T011: the engine client against a stub engine, no Spring. */
class EngineClientTest {

  static final WireMockServer ENGINE =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final UUID GUEST = UUID.fromString("11111111-1111-4111-8111-111111111111");
  static final UUID OTHER = UUID.fromString("22222222-2222-4222-8222-222222222222");

  EngineClient client;

  @BeforeAll
  static void start() {
    ENGINE.start();
  }

  @AfterAll
  static void stop() {
    ENGINE.stop();
  }

  @BeforeEach
  void build() {
    ENGINE.resetAll();
    client =
        EngineClients.build(
            new ConnectionConfig(
                "alpha",
                "acme",
                ENGINE.baseUrl(),
                "alpha-key",
                "ACME",
                "id",
                "secret",
                List.of(),
                "s"));
  }

  @Test
  @DisplayName("registering the source system treats a conflict as already registered")
  void registerAcceptsCreatedAndConflict() {
    ENGINE.stubFor(
        post(urlPathEqualTo("/api/v1/source-systems")).willReturn(aResponse().withStatus(201)));
    client.registerSourceSystem("apaleo", "Apaleo");
    ENGINE.stubFor(
        post(urlPathEqualTo("/api/v1/source-systems")).willReturn(aResponse().withStatus(409)));
    client.registerSourceSystem("apaleo", "Apaleo");
    ENGINE.stubFor(
        post(urlPathEqualTo("/api/v1/source-systems")).willReturn(aResponse().withStatus(500)));

    assertThatThrownBy(() -> client.registerSourceSystem("apaleo", "Apaleo"))
        .isInstanceOf(EngineException.class);
    ENGINE.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/source-systems"))
            .withHeader("X-API-Key", equalTo("alpha-key")));
  }

  @Test
  @DisplayName("a submitted batch answers one result per record, read in full")
  void submitReadsEveryResult() {
    ENGINE.stubFor(
        post(urlPathEqualTo("/api/v1/records"))
            .willReturn(
                json(
                    """
                    {"results":[
                      {"externalKey":"R-1:primaryGuest:t","sourceRecordId":"%1$s","guestId":"%2$s","status":"CREATED_GUEST","needsReview":false,"pendingReviewIds":[]},
                      {"externalKey":"R-1:booker:t","sourceRecordId":"%1$s","guestId":"%2$s","status":"DUPLICATE_IGNORED","needsReview":true,"pendingReviewIds":["%3$s"]},
                      {"externalKey":"R-2:primaryGuest:t","sourceRecordId":null,"guestId":null,"status":"ERROR","needsReview":false,"pendingReviewIds":[],"problem":{"detail":"boom"}}
                    ]}
                    """
                        .formatted(OTHER, GUEST, OTHER))));
    IngestRecord record =
        new IngestRecord(
            "apaleo",
            "R-1:primaryGuest:t",
            "2026-07-09T14:30:00Z",
            Map.of("firstName", "Anna"),
            new SourceObject(
                "reservation", "R-1", "PRIMARY_GUEST", null, "2026-07-09T14:30:00Z", null, null));

    List<IngestResult> results = client.submit(List.of(record));

    assertThat(results).hasSize(3);
    assertThat(results.get(0).guestId()).isEqualTo(GUEST);
    assertThat(results.get(0).failed()).isFalse();
    assertThat(results.get(1).duplicate()).isTrue();
    assertThat(results.get(1).needsReview()).isTrue();
    assertThat(results.get(1).pendingReviewIds()).containsExactly(OTHER);
    assertThat(results.get(2).failed()).isTrue();
    assertThat(results.get(2).problem()).containsEntry("detail", "boom");
    ENGINE.verify(
        postRequestedFor(urlPathEqualTo("/api/v1/records"))
            .withHeader("X-API-Key", equalTo("alpha-key"))
            .withRequestBody(
                com.github.tomakehurst.wiremock.client.WireMock.containing("\"sourceObject\"")));
  }

  @Test
  @DisplayName("a batch is at most one hundred records; a failing engine is an exception")
  void submitLimitsAndFails() {
    ENGINE.stubFor(post(urlPathEqualTo("/api/v1/records")).willReturn(aResponse().withStatus(503)));
    List<IngestRecord> tooMany =
        Collections.nCopies(
            EngineClient.BATCH + 1, new IngestRecord("apaleo", "k", "t", Map.of(), null));

    assertThatThrownBy(() -> client.submit(tooMany)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> client.submit(tooMany.subList(0, 1)))
        .isInstanceOf(EngineException.class);
  }

  @Test
  @DisplayName(
      "a guest read answers its status: active, merged with the current id, or never existed")
  void getGuestReadsTheStatus() {
    ENGINE.stubFor(
        get(urlPathEqualTo("/api/v1/guests/" + GUEST))
            .willReturn(json("{\"id\":\"" + GUEST + "\",\"status\":\"ACTIVE\",\"profile\":{}}")));
    ENGINE.stubFor(
        get(urlPathEqualTo("/api/v1/guests/" + OTHER))
            .willReturn(
                json(
                    "{\"id\":\""
                        + OTHER
                        + "\",\"status\":\"MERGED\",\"currentGuestIds\":[\""
                        + GUEST
                        + "\"],\"hops\":[]}")));
    UUID unknown = UUID.randomUUID();
    ENGINE.stubFor(
        get(urlPathEqualTo("/api/v1/guests/" + unknown))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody("{\"status\":404}")));

    Optional<GuestResolution> active = client.getGuest(GUEST);
    Optional<GuestResolution> merged = client.getGuest(OTHER);
    Optional<GuestResolution> gone = client.getGuest(unknown);

    assertThat(active).isPresent();
    assertThat(active.get().active()).isTrue();
    assertThat(merged.get().status()).isEqualTo("MERGED");
    assertThat(merged.get().currentGuestIds()).containsExactly(GUEST);
    assertThat(gone).isEmpty();
  }

  private static ResponseDefinitionBuilder json(String body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }
}
