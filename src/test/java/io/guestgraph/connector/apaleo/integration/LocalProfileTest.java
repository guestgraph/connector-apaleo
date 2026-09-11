package io.guestgraph.connector.apaleo.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Connections;
import io.guestgraph.connector.apaleo.config.ConnectorProperties;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Spec 007 task T023: the local profile starts the connector with nothing written by hand: no
 * CONNECTOR_* variable set, the sample connections file read, the sync at boot off.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class LocalProfileTest {

  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    // The sample credentials reach no Apaleo; a closed local port fails fast instead of the
    // network.
    registry.add("connector.apaleo.identity-url", () -> "http://localhost:1");
    registry.add("connector.apaleo.api-url", () -> "http://localhost:1");
    registry.add("connector.apaleo.webhook-url", () -> "http://localhost:1");
  }

  @LocalServerPort int port;
  @Autowired Connections connections;
  @Autowired ConnectorProperties properties;

  @Test
  @DisplayName("the local profile reads the sample connection and does not sync at boot")
  void localProfileStartsWithTheSampleConnection() {
    Optional<ConnectionConfig> local = connections.byName("local");

    assertThat(local).isPresent();
    assertThat(local.get().engineBaseUrl()).isEqualTo("http://localhost:8080");
    assertThat(properties.syncOnBoot()).isFalse();
    assertThat(properties.publicUrl()).isEqualTo("http://localhost:8081");

    ResponseEntity<String> status =
        RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(s -> true, (request, response) -> {})
            .build()
            .get()
            .uri("/status")
            .header("Authorization", "Bearer local-ops-token")
            .retrieve()
            .toEntity(String.class);
    assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(status.getBody()).contains("\"id\":\"local\"");
  }
}
