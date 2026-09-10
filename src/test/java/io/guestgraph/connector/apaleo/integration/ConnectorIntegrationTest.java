package io.guestgraph.connector.apaleo.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared harness (spec 005, task T005): one PostgreSQL container for the run, one WireMock standing
 * in for Apaleo and one for the engine, and two connections, {@code alpha} and {@code beta}, each
 * with its own Apaleo credential, engine key and webhook secret. Every stub is per connection: the
 * token call matches the connection's Basic credential and answers its own token, every Apaleo read
 * matches that token as a Bearer, and every engine call matches the connection's key — so a call
 * made under one connection is never served by the other's stub.
 *
 * <p>A baseline of stubs the application needs to start — tokens, the source-system registration,
 * an empty subscription list and a created subscription — is installed before the context boots and
 * again after every reset, so startup work never meets an empty stub.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.docker.compose.enabled=false")
public abstract class ConnectorIntegrationTest {

  /** One configured connection as the harness knows it. */
  protected record Connection(
      String name,
      String tenantLabel,
      String account,
      String propertyId,
      String clientId,
      String clientSecret,
      String token,
      String engineKey,
      String webhookSecret) {

    String basicCredential() {
      return "Basic "
          + Base64.getEncoder()
              .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    }

    String bearer() {
      return "Bearer " + token;
    }
  }

  protected static final Connection ALPHA =
      new Connection(
          "alpha",
          "acme",
          "ACME",
          "BER",
          "acme-client",
          "acme-secret",
          "test-token-alpha",
          "alpha-engine-key",
          "alpha-secret");
  protected static final Connection BETA =
      new Connection(
          "beta",
          "globex",
          "GLOBEX",
          "MUC",
          "globex-client",
          "globex-secret",
          "test-token-beta",
          "beta-engine-key",
          "beta-secret");
  protected static final List<Connection> CONNECTIONS = List.of(ALPHA, BETA);
  protected static final String OPS_TOKEN = "ops-token";

  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");
  static final WireMockServer APALEO =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final WireMockServer ENGINE =
      new WireMockServer(
          WireMockConfiguration.options()
              .dynamicPort()
              .extensions(new IngestResponseTransformer()));
  static final Path CONNECTIONS_FILE;

  static {
    POSTGRES.start();
    APALEO.start();
    ENGINE.start();
    CONNECTIONS_FILE = writeConnectionsFile();
    baseline();
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

  private static Path writeConnectionsFile() {
    StringBuilder yaml = new StringBuilder("connections:\n");
    for (Connection c : CONNECTIONS) {
      yaml.append(
          """
            %s:
              tenantLabel: %s
              engineBaseUrl: %s
              engineApiKey: %s
              apaleoAccount: %s
              apaleoClientId: %s
              apaleoClientSecret: %s
              apaleoPropertyIds: [%s]
              webhookSecret: %s
          """
              .formatted(
                  c.name(),
                  c.tenantLabel(),
                  ENGINE.baseUrl(),
                  c.engineKey(),
                  c.account(),
                  c.clientId(),
                  c.clientSecret(),
                  c.propertyId(),
                  c.webhookSecret()));
    }
    try {
      Path file = Files.createTempFile("connections", ".yaml");
      Files.writeString(file, yaml.toString());
      file.toFile().deleteOnExit();
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @LocalServerPort protected int port;

  /** Wipe stubs and journals, then restore what startup and every test relies on. */
  @BeforeEach
  void resetStubs() {
    APALEO.resetAll();
    ENGINE.resetAll();
    IngestResponseTransformer.clearOverrides();
    baseline();
  }

  static void baseline() {
    for (Connection c : CONNECTIONS) {
      stubToken(c);
      stubIngest(c);
    }
    ENGINE.stubFor(
        post(urlPathEqualTo("/api/v1/source-systems")).willReturn(aResponse().withStatus(201)));
    APALEO.stubFor(get(urlPathEqualTo("/v1/subscriptions")).willReturn(json("[]")));
    APALEO.stubFor(
        post(urlPathEqualTo("/v1/subscriptions"))
            .willReturn(json("{\"id\":\"sub-1\"}").withStatus(201)));
  }

  // --- Apaleo stubs, per connection -----------------------------------------------------

  /** The client-credentials call: the connection's Basic credential earns its own token. */
  protected static void stubToken(Connection c) {
    APALEO.stubFor(
        post(urlPathEqualTo("/connect/token"))
            .withHeader("Authorization", equalTo(c.basicCredential()))
            .willReturn(
                json(
                    "{\"access_token\":\"%s\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"
                        .formatted(c.token()))));
  }

  protected static void stubReservation(Connection c, String id, String documentName) {
    APALEO.stubFor(
        asConnection(get(urlPathEqualTo("/booking/v1/reservations/" + id)), c)
            .willReturn(json(document(documentName))));
  }

  /** Bookings are read with their reservations expanded; a fetch without asking is not served. */
  protected static void stubBooking(Connection c, String id, String documentName) {
    APALEO.stubFor(
        asConnection(get(urlPathEqualTo("/booking/v1/bookings/" + id)), c)
            .withQueryParam("expand", equalTo("reservations"))
            .willReturn(json(document(documentName))));
  }

  /** Pages answer in order; the page after the last is Apaleo's 204 No Content. */
  protected static void stubReservationPages(Connection c, List<String> documentNames) {
    stubPages(c, "/booking/v1/reservations", documentNames, false);
  }

  protected static void stubBookingPages(Connection c, List<String> documentNames) {
    stubPages(c, "/booking/v1/bookings", documentNames, true);
  }

  private static void stubPages(
      Connection c, String path, List<String> documentNames, boolean expandReservations) {
    for (int i = 0; i <= documentNames.size(); i++) {
      MappingBuilder page =
          asConnection(get(urlPathEqualTo(path)), c)
              .withQueryParam("pageNumber", equalTo(String.valueOf(i + 1)));
      if (expandReservations) {
        page = page.withQueryParam("expand", equalTo("reservations"));
      }
      APALEO.stubFor(
          page.willReturn(
              i < documentNames.size()
                  ? json(document(documentNames.get(i)))
                  : aResponse().withStatus(204)));
    }
  }

  private static MappingBuilder asConnection(MappingBuilder builder, Connection c) {
    return builder.withHeader("Authorization", equalTo(c.bearer()));
  }

  // --- engine stubs, per connection -----------------------------------------------------

  /** Ingest under the connection's key answers one result per record (the transformer). */
  protected static void stubIngest(Connection c) {
    ENGINE.stubFor(
        post(urlPathEqualTo("/api/v1/records"))
            .withHeader("X-API-Key", equalTo(c.engineKey()))
            .willReturn(aResponse().withTransformers(IngestResponseTransformer.NAME)));
  }

  /** Make the next ingest answer this status for one external key: DUPLICATE_IGNORED or ERROR. */
  protected static void answerIngest(String externalKey, String status) {
    IngestResponseTransformer.override(externalKey, status);
  }

  protected static void stubGuest(Connection c, String guestId, String body) {
    ENGINE.stubFor(
        get(urlPathEqualTo("/api/v1/guests/" + guestId))
            .withHeader("X-API-Key", equalTo(c.engineKey()))
            .willReturn(json(body)));
  }

  // --- helpers --------------------------------------------------------------------------

  protected RestClient connector() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(status -> true, (request, response) -> {})
        .build();
  }

  /** A recorded Apaleo document, from the test classpath. */
  protected static String document(String name) {
    try (InputStream in = ConnectorIntegrationTest.class.getResourceAsStream("/apaleo/" + name)) {
      if (in == null) {
        throw new IllegalArgumentException("No recorded document " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
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
