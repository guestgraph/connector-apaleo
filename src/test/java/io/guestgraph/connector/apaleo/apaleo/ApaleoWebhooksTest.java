package io.guestgraph.connector.apaleo.apaleo;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.guestgraph.connector.apaleo.config.Http;
import java.time.Clock;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spec 005 task T022: the subscription is created, replaced or left alone at start. */
class ApaleoWebhooksTest {

  static final WireMockServer APALEO =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final String ENDPOINT = "https://connector.example/apaleo/events/s3cret";
  static final List<String> EVENTS = List.of("reservation/created", "reservation/changed");
  static final Predicate<String> STALE =
      url -> url.startsWith("https://connector.example/apaleo/events/");

  ApaleoWebhooks webhooks;

  @BeforeAll
  static void start() {
    APALEO.start();
  }

  @AfterAll
  static void stop() {
    APALEO.stop();
  }

  @BeforeEach
  void build() {
    APALEO.resetAll();
    APALEO.stubFor(
        post(urlPathEqualTo("/connect/token"))
            .willReturn(json("{\"access_token\":\"tok\",\"expires_in\":3600}")));
    APALEO.stubFor(
        post(urlPathEqualTo("/v1/subscriptions"))
            .willReturn(json("{\"id\":\"new\"}").withStatus(201)));
    APALEO.stubFor(
        put(urlPathEqualTo("/v1/subscriptions/old")).willReturn(aResponse().withStatus(204)));
    ApaleoAuth auth =
        new ApaleoAuth(
            Http.client()
                .baseUrl(APALEO.baseUrl())
                .defaultStatusHandler(s -> true, (r, s) -> {})
                .build(),
            "id",
            "secret",
            Clock.systemUTC());
    webhooks =
        new ApaleoWebhooks(
            Http.client()
                .baseUrl(APALEO.baseUrl())
                .defaultStatusHandler(s -> true, (r, s) -> {})
                .build(),
            auth);
  }

  @Test
  @DisplayName("with no subscription, one is created for the endpoint, events and properties")
  void createdWhenNone() {
    APALEO.stubFor(get(urlPathEqualTo("/v1/subscriptions")).willReturn(json("[]")));

    ApaleoWebhooks.Subscription result = webhooks.ensure(ENDPOINT, EVENTS, List.of("BER"), STALE);

    assertThat(result.id()).isEqualTo("new");
    APALEO.verify(
        postRequestedFor(urlPathEqualTo("/v1/subscriptions"))
            .withRequestBody(containing("\"endpointUrl\":\"" + ENDPOINT + "\""))
            .withRequestBody(containing("reservation/created"))
            .withRequestBody(containing("\"propertyIds\":[\"BER\"]")));
  }

  @Test
  @DisplayName("a subscription for the endpoint with other events or properties is replaced")
  void replacedWhenDifferent() {
    APALEO.stubFor(
        get(urlPathEqualTo("/v1/subscriptions"))
            .willReturn(
                json(listing("old", ENDPOINT, List.of("reservation/created"), List.of("BER")))));

    ApaleoWebhooks.Subscription result = webhooks.ensure(ENDPOINT, EVENTS, List.of("BER"), STALE);

    assertThat(result.id()).isEqualTo("old");
    APALEO.verify(
        1,
        putRequestedFor(urlPathEqualTo("/v1/subscriptions/old"))
            .withRequestBody(containing("reservation/changed")));
    APALEO.verify(0, postRequestedFor(urlPathEqualTo("/v1/subscriptions")));
  }

  @Test
  @DisplayName("a matching subscription is left alone, and exists() sees it")
  void leftAloneWhenMatching() {
    APALEO.stubFor(
        get(urlPathEqualTo("/v1/subscriptions"))
            .willReturn(json(listing("same", ENDPOINT, EVENTS, List.of("BER")))));

    ApaleoWebhooks.Subscription result = webhooks.ensure(ENDPOINT, EVENTS, List.of("BER"), STALE);

    assertThat(result.id()).isEqualTo("same");
    APALEO.verify(0, postRequestedFor(urlPathEqualTo("/v1/subscriptions")));
    APALEO.verify(0, putRequestedFor(urlPathEqualTo("/v1/subscriptions/same")));
    assertThat(webhooks.exists(ENDPOINT)).isTrue();
    assertThat(webhooks.exists("https://elsewhere.example/hook")).isFalse();
  }

  @Test
  @DisplayName("a subscription under an earlier secret is moved to the endpoint")
  void movedFromAStaleEndpoint() {
    APALEO.stubFor(
        get(urlPathEqualTo("/v1/subscriptions"))
            .willReturn(
                json(
                    listing(
                        "old",
                        "https://connector.example/apaleo/events/rotated-away",
                        EVENTS,
                        List.of("BER")))));

    ApaleoWebhooks.Subscription result = webhooks.ensure(ENDPOINT, EVENTS, List.of("BER"), STALE);

    assertThat(result.id()).isEqualTo("old");
    APALEO.verify(
        1,
        putRequestedFor(urlPathEqualTo("/v1/subscriptions/old"))
            .withRequestBody(containing("\"endpointUrl\":\"" + ENDPOINT + "\"")));
    APALEO.verify(0, postRequestedFor(urlPathEqualTo("/v1/subscriptions")));
    assertThat(webhooks.exists(ENDPOINT)).as("the listing is what Apaleo still says").isFalse();
  }

  private static String listing(
      String id, String endpoint, List<String> events, List<String> properties) {
    return """
        [{"id":"%s","endpointUrl":"%s","topics":[],"events":[%s],"propertyIds":[%s],"created":"2026-09-01T00:00:00Z"}]
        """
        .formatted(id, endpoint, quoted(events), quoted(properties));
  }

  private static String quoted(List<String> values) {
    return String.join(",", values.stream().map(v -> "\"" + v + "\"").toList());
  }

  private static ResponseDefinitionBuilder json(String body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }
}
