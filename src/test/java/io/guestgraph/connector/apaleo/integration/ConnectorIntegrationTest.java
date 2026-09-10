package io.guestgraph.connector.apaleo.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared harness (spec 005, tasks T005): one PostgreSQL container for the run, one WireMock
 * standing in for Apaleo and one for the engine, and two connections, {@code alpha} and {@code
 * beta}, each with its own webhook secret and engine key, so that isolation between connections is
 * proven rather than assumed. Every stub is per connection: a delivery, a fetch or a guest answer
 * belongs to the connection whose stubs it hits.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.docker.compose.enabled=false")
public abstract class ConnectorIntegrationTest {

  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");
  static final WireMockServer APALEO =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final WireMockServer ENGINE =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  protected static final String ALPHA = "alpha";
  protected static final String BETA = "beta";
  protected static final String ALPHA_SECRET = "alpha-secret";
  protected static final String BETA_SECRET = "beta-secret";
  protected static final String ALPHA_ENGINE_KEY = "alpha-engine-key";
  protected static final String BETA_ENGINE_KEY = "beta-engine-key";
  protected static final String OPS_TOKEN = "ops-token";

  static final Path CONNECTIONS_FILE;

  static {
    POSTGRES.start();
    APALEO.start();
    ENGINE.start();
    CONNECTIONS_FILE = writeConnectionsFile();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("connector.public-url", () -> "https://connector.example");
    registry.add("connector.ops-token", () -> OPS_TOKEN);
    registry.add("connector.connections-file", () -> CONNECTIONS_FILE.toString());
    registry.add("connector.apaleo.identity-url", APALEO::baseUrl);
    registry.add("connector.apaleo.api-url", APALEO::baseUrl);
    registry.add("connector.apaleo.webhook-url", APALEO::baseUrl);
  }

  /** Two connections against the same two stubs, told apart by key, secret and account. */
  private static Path writeConnectionsFile() {
    String yaml =
        """
        connections:
          alpha:
            tenantLabel: acme
            engineBaseUrl: %1$s
            engineApiKey: %2$s
            apaleoAccount: ACME
            apaleoClientId: acme-client
            apaleoClientSecret: acme-secret
            apaleoPropertyIds: [BER]
            webhookSecret: %3$s
          beta:
            tenantLabel: globex
            engineBaseUrl: %1$s
            engineApiKey: %4$s
            apaleoAccount: GLOBEX
            apaleoClientId: globex-client
            apaleoClientSecret: globex-secret
            apaleoPropertyIds: [MUC]
            webhookSecret: %5$s
        """
            .formatted(
                ENGINE.baseUrl(), ALPHA_ENGINE_KEY, ALPHA_SECRET, BETA_ENGINE_KEY, BETA_SECRET);
    try {
      Path file = Files.createTempFile("connections", ".yaml");
      Files.writeString(file, yaml);
      file.toFile().deleteOnExit();
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @LocalServerPort protected int port;

  @BeforeEach
  void resetStubs() {
    APALEO.resetAll();
    ENGINE.resetAll();
    stubToken();
  }

  // --- Apaleo stubs ---------------------------------------------------------------------

  /** Every connection's client-credentials call answers the recorded token. */
  protected void stubToken() {
    APALEO.stubFor(post(urlPathEqualTo("/connect/token")).willReturn(json(document("token.json"))));
  }

  protected void stubReservation(String id, String documentName) {
    APALEO.stubFor(
        get(urlPathEqualTo("/booking/v1/reservations/" + id))
            .willReturn(json(document(documentName))));
  }

  protected void stubBooking(String id, String documentName) {
    APALEO.stubFor(
        get(urlPathEqualTo("/booking/v1/bookings/" + id)).willReturn(json(document(documentName))));
  }

  /** Pages answer in order; the page after the last is Apaleo's 204 No Content. */
  protected void stubReservationPages(List<String> documentNames) {
    for (int i = 0; i < documentNames.size(); i++) {
      APALEO.stubFor(
          get(urlPathEqualTo("/booking/v1/reservations"))
              .withQueryParam("pageNumber", equalTo(String.valueOf(i + 1)))
              .willReturn(json(document(documentNames.get(i)))));
    }
    APALEO.stubFor(
        get(urlPathEqualTo("/booking/v1/reservations"))
            .withQueryParam("pageNumber", equalTo(String.valueOf(documentNames.size() + 1)))
            .willReturn(aResponse().withStatus(204)));
  }

  protected void stubBookingPages(List<String> documentNames) {
    for (int i = 0; i < documentNames.size(); i++) {
      APALEO.stubFor(
          get(urlPathEqualTo("/booking/v1/bookings"))
              .withQueryParam("pageNumber", equalTo(String.valueOf(i + 1)))
              .willReturn(json(document(documentNames.get(i)))));
    }
    APALEO.stubFor(
        get(urlPathEqualTo("/booking/v1/bookings"))
            .withQueryParam("pageNumber", equalTo(String.valueOf(documentNames.size() + 1)))
            .willReturn(aResponse().withStatus(204)));
  }

  // --- engine stubs ---------------------------------------------------------------------

  /** The engine answers every record of a batch with the given per-record result bodies. */
  protected void stubIngest(String engineKey, String resultsJsonArray) {
    ENGINE.stubFor(
        post(urlPathEqualTo("/api/v1/records"))
            .withHeader("X-API-Key", equalTo(engineKey))
            .willReturn(json("{\"results\":" + resultsJsonArray + "}")));
  }

  protected void stubGuest(String engineKey, String guestId, String body) {
    ENGINE.stubFor(
        get(urlPathEqualTo("/api/v1/guests/" + guestId))
            .withHeader("X-API-Key", equalTo(engineKey))
            .willReturn(json(body)));
  }

  protected void stubSourceSystemRegistration() {
    ENGINE.stubFor(
        post(urlPathEqualTo("/api/v1/source-systems")).willReturn(aResponse().withStatus(201)));
  }

  // --- helpers --------------------------------------------------------------------------

  protected RestClient connector() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(status -> true, (request, response) -> {})
        .build();
  }

  protected static String document(String name) {
    try {
      return Files.readString(Path.of("src/test/resources/apaleo", name));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static ResponseDefinitionBuilder json(String body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }
}
