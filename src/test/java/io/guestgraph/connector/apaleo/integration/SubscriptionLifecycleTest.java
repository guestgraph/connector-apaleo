package io.guestgraph.connector.apaleo.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.connector.apaleo.apaleo.ApaleoClients;
import io.guestgraph.connector.apaleo.api.events.Subscriptions;
import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Connections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Spec 009: an operator takes a connection's subscription away before a teardown, and puts it back
 * without a restart. What the connector asked Apaleo to do is the behavior under test, so every
 * assertion reads the stub's record of the request rather than the connector's own memory.
 */
class SubscriptionLifecycleTest extends ConnectorIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired ApaleoClients apaleo;
  @Autowired Connections connections;
  @Autowired Subscriptions subscriptions;

  /** The subscription Apaleo holds for a connection, as its listing would carry it. */
  private static String listingFor(Connection c, String id) {
    return "[{\"id\":\"%s\",\"endpointUrl\":\"%s\",\"events\":[],\"propertyIds\":[]}]"
        .formatted(id, endpointOf(c));
  }

  @BeforeEach
  void resetStubs() {
    APALEO.resetRequests();
  }

  private ConnectionConfig alpha() {
    return connections.byName(ALPHA.name()).orElseThrow();
  }

  @Test
  @DisplayName("the webhook client deletes a subscription by its id")
  void clientDeletes() {
    APALEO.stubFor(
        asConnection(delete(urlPathEqualTo("/v1/subscriptions/sub-alpha")), ALPHA)
            .willReturn(aResponse().withStatus(204)));

    apaleo.webhooksFor(alpha()).delete("sub-alpha");

    APALEO.verify(
        deleteRequestedFor(urlPathEqualTo("/v1/subscriptions/sub-alpha"))
            .withHeader("Authorization", equalTo("Bearer " + ALPHA.token())));
  }

  @Test
  @DisplayName("what the listing holds is what the client finds")
  void clientFinds() {
    stubSubscriptions(ALPHA, listingFor(ALPHA, "sub-alpha"));

    assertThat(apaleo.webhooksFor(alpha()).find(endpointOf(ALPHA)))
        .get()
        .satisfies(s -> assertThat(s.id()).isEqualTo("sub-alpha"));
  }

  // --- the removal (user story 1) -------------------------------------------------------

  @Test
  @DisplayName("a removal deletes the subscription that names this connection")
  void removalDeletes() {
    stubSubscriptions(ALPHA, listingFor(ALPHA, "sub-alpha"));
    stubDelete(ALPHA, "sub-alpha");

    JsonNode body = removed(ops().delete().uri("/connections/alpha/subscription"), 200);

    assertThat(body.get("state").asString()).isEqualTo("REMOVED");
    assertThat(body.get("removed").asBoolean()).isTrue();
    assertThat(body.get("endpoint").asString()).isEqualTo(endpointOf(ALPHA));
    APALEO.verify(
        deleteRequestedFor(urlPathEqualTo("/v1/subscriptions/sub-alpha"))
            .withHeader("Authorization", equalTo(ALPHA.bearer())));
  }

  @Test
  @DisplayName("removing twice, or removing nothing, is success both times")
  void removalIsIdempotent() {
    stubSubscriptions(ALPHA, listingFor(ALPHA, "sub-alpha"));
    stubDelete(ALPHA, "sub-alpha");
    removed(ops().delete().uri("/connections/alpha/subscription"), 200);

    // Apaleo now lists none, as it would after the first delete.
    stubSubscriptions(ALPHA, "[]");
    JsonNode second = removed(ops().delete().uri("/connections/alpha/subscription"), 200);
    JsonNode third = removed(ops().delete().uri("/connections/alpha/subscription"), 200);

    for (JsonNode body : new JsonNode[] {second, third}) {
      assertThat(body.get("state").asString()).isEqualTo("REMOVED");
      assertThat(body.get("removed").asBoolean()).isFalse();
      assertThat(body.get("endpoint").isNull()).isTrue();
    }
    APALEO.verify(1, deleteRequestedFor(urlPathEqualTo("/v1/subscriptions/sub-alpha")));
  }

  @Test
  @DisplayName("a removal changes nothing else about the connection")
  void removalTouchesNothingElse() {
    stubSubscriptions(ALPHA, listingFor(ALPHA, "sub-alpha"));
    stubDelete(ALPHA, "sub-alpha");
    JsonNode before = connectionStatus("alpha");

    removed(ops().delete().uri("/connections/alpha/subscription"), 200);

    JsonNode after = connectionStatus("alpha");
    for (String field :
        new String[] {"counters", "syncPoints", "pendingEvents", "splitsAwaitingPerson"}) {
      assertThat(after.get(field)).as(field).isEqualTo(before.get(field));
    }
  }

  @Test
  @DisplayName("the refusals: no token, no such connection, and Apaleo saying no")
  void removalRefusals() {
    assertProblem(
        connector()
            .delete()
            .uri("/connections/alpha/subscription")
            .retrieve()
            .toEntity(String.class),
        401,
        "unauthorized");
    assertProblem(
        ops().delete().uri("/connections/nope/subscription").retrieve().toEntity(String.class),
        404,
        "not-found");

    stubSubscriptions(ALPHA, listingFor(ALPHA, "sub-alpha"));
    APALEO.stubFor(
        asConnection(delete(urlPathEqualTo("/v1/subscriptions/sub-alpha")), ALPHA)
            .willReturn(aResponse().withStatus(500)));
    assertProblem(
        ops().delete().uri("/connections/alpha/subscription").retrieve().toEntity(String.class),
        502,
        "apaleo-unreachable");
    // Nothing was reported removed that was not: Apaleo still holds it.
    assertThat(apaleo.webhooksFor(alpha()).find(endpointOf(ALPHA))).isPresent();
  }

  @Test
  @DisplayName("one connection's removal leaves another's subscription alone")
  void removalIsPerConnection() {
    stubSubscriptions(ALPHA, listingFor(ALPHA, "sub-alpha"));
    stubSubscriptions(BETA, listingFor(BETA, "sub-beta"));
    stubDelete(ALPHA, "sub-alpha");

    removed(ops().delete().uri("/connections/alpha/subscription"), 200);

    APALEO.verify(0, deleteRequestedFor(urlPathEqualTo("/v1/subscriptions/sub-beta")));
    assertThat(apaleo.webhooksFor(beta()).find(endpointOf(BETA))).isPresent();
  }

  // --- housekeeping and the way back (user story 3) --------------------------------------

  @Test
  @DisplayName("a reconciliation leaves a removal alone and does not call it a fault")
  void reconciliationLeavesARemovalAlone() {
    stubSubscriptions(ALPHA, listingFor(ALPHA, "sub-alpha"));
    stubDelete(ALPHA, "sub-alpha");
    removed(ops().delete().uri("/connections/alpha/subscription"), 200);
    stubSubscriptions(ALPHA, "[]");
    APALEO.resetRequests();

    subscriptions.check(alpha());

    assertThat(subscriptions.status(alpha()).state()).isEqualTo(Subscriptions.State.REMOVED);
    APALEO.verify(0, postRequestedFor(urlPathEqualTo("/v1/subscriptions")));
  }

  @Test
  @DisplayName("a subscription that reappeared is active, whatever was asked for")
  void apaleosAnswerWins() {
    stubSubscriptions(ALPHA, listingFor(ALPHA, "sub-alpha"));
    stubDelete(ALPHA, "sub-alpha");
    removed(ops().delete().uri("/connections/alpha/subscription"), 200);

    // Apaleo lists one again — somebody put it back, or the delete did not take.
    subscriptions.check(alpha());

    assertThat(subscriptions.status(alpha()).state()).isEqualTo(Subscriptions.State.ACTIVE);
  }

  @Test
  @DisplayName("a restore creates the subscription again, without a restart")
  void restoreCreates() {
    stubSubscriptions(ALPHA, listingFor(ALPHA, "sub-alpha"));
    stubDelete(ALPHA, "sub-alpha");
    removed(ops().delete().uri("/connections/alpha/subscription"), 200);
    stubSubscriptions(ALPHA, "[]");
    APALEO.resetRequests();

    JsonNode body = removed(ops().put().uri("/connections/alpha/subscription"), 200);

    assertThat(body.get("state").asString()).isEqualTo("ACTIVE");
    assertThat(body.get("id").asString()).isNotBlank();
    APALEO.verify(1, postRequestedFor(urlPathEqualTo("/v1/subscriptions")));
  }

  @Test
  @DisplayName("a restore refuses the same three ways a removal does")
  void restoreRefusals() {
    assertProblem(
        connector().put().uri("/connections/alpha/subscription").retrieve().toEntity(String.class),
        401,
        "unauthorized");
    assertProblem(
        ops().put().uri("/connections/nope/subscription").retrieve().toEntity(String.class),
        404,
        "not-found");

    stubSubscriptionsFailure(ALPHA, 500);
    assertProblem(
        ops().put().uri("/connections/alpha/subscription").retrieve().toEntity(String.class),
        502,
        "apaleo-unreachable");
  }

  @Test
  @DisplayName("a listing Apaleo could not give is not an empty listing")
  void aFailedListingIsNotEmpty() {
    stubSubscriptionsFailure(ALPHA, 500);

    // A removal must not answer "there was nothing to remove" when Apaleo simply did not say.
    assertProblem(
        ops().delete().uri("/connections/alpha/subscription").retrieve().toEntity(String.class),
        502,
        "apaleo-unreachable");
    APALEO.verify(0, postRequestedFor(urlPathEqualTo("/v1/subscriptions")));
  }

  // --- helpers --------------------------------------------------------------------------

  private static void stubDelete(Connection c, String id) {
    APALEO.stubFor(
        asConnection(delete(urlPathEqualTo("/v1/subscriptions/" + id)), c)
            .willReturn(aResponse().withStatus(204)));
  }

  private ConnectionConfig beta() {
    return connections.byName(BETA.name()).orElseThrow();
  }

  private RestClient ops() {
    return connector().mutate().defaultHeader("Authorization", "Bearer " + OPS_TOKEN).build();
  }

  private JsonNode removed(RestClient.RequestHeadersSpec<?> request, int status) {
    ResponseEntity<String> response = request.retrieve().toEntity(String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(status);
    return JSON.readTree(response.getBody());
  }

  private JsonNode connectionStatus(String name) {
    JsonNode status = JSON.readTree(ops().get().uri("/status").retrieve().body(String.class));
    for (JsonNode connection : status.get("connections")) {
      if (name.equals(connection.get("id").asString())) {
        return connection;
      }
    }
    throw new AssertionError("no connection " + name + " in the status");
  }

  private void assertProblem(ResponseEntity<String> response, int status, String slug) {
    assertThat(response.getStatusCode().value()).isEqualTo(status);
    JsonNode problem = JSON.readTree(response.getBody());
    assertThat(problem.get("type").asString()).isEqualTo("https://guestgraph.io/problems/#" + slug);
    assertThat(problem.get("detail").asString()).isNotBlank();
  }
}
