package io.guestgraph.connector.apaleo.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.connector.apaleo.apaleo.ApaleoClients;
import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Connections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Spec 009: an operator takes a connection's subscription away before a teardown, and puts it back
 * without a restart. What the connector asked Apaleo to do is the behavior under test, so every
 * assertion reads the stub's record of the request rather than the connector's own memory.
 */
class SubscriptionLifecycleTest extends ConnectorIntegrationTest {

  @Autowired ApaleoClients apaleo;
  @Autowired Connections connections;

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
}
