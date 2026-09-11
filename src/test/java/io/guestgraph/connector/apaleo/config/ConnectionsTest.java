package io.guestgraph.connector.apaleo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spec 005 FR-015a: connections come from configuration; a shared secret is refused. */
class ConnectionsTest {

  static ConnectionConfig connection(String name, String secret) {
    return new ConnectionConfig(
        name, "t", "http://engine", "key-" + name, "ACC", "id", "s3cret", List.of("BER"), secret);
  }

  @Test
  @DisplayName("a connection is found by its name and by the secret in a delivery's path")
  void lookupByNameAndSecret() {
    Connections connections =
        new Connections(List.of(connection("alpha", "a-secret"), connection("beta", "b-secret")));

    assertThat(connections.byName("alpha")).map(ConnectionConfig::name).contains("alpha");
    assertThat(connections.bySecret("b-secret")).map(ConnectionConfig::name).contains("beta");
    assertThat(connections.bySecret("nobody")).isEmpty();
    assertThat(connections.all()).hasSize(2);
  }

  @Test
  @DisplayName("two connections sharing a webhook secret or a name are refused")
  void duplicatesAreRefused() {
    assertThatThrownBy(
            () -> new Connections(List.of(connection("alpha", "same"), connection("beta", "same"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("webhook secret");
    assertThatThrownBy(
            () -> new Connections(List.of(connection("alpha", "a"), connection("alpha", "b"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("named alpha");
  }

  @Test
  @DisplayName("a file is read as written: digits stay strings, a missing field is refused")
  void fileIsReadAsWritten() throws IOException {
    Path file = Files.createTempFile("connections", ".yaml");
    Files.writeString(
        file,
        """
        connections:
          alpha:
            tenantLabel: acme
            engineBaseUrl: http://engine
            engineApiKey: 0123456789
            apaleoAccount: ACME
            apaleoClientId: acme
            apaleoClientSecret: 1e5
            apaleoPropertyIds: [BER, 007]
            webhookSecret: 0042
        """);

    Connections connections = Connections.from(file);

    ConnectionConfig alpha = connections.byName("alpha").orElseThrow();
    assertThat(alpha.engineApiKey()).isEqualTo("0123456789");
    assertThat(alpha.apaleoClientSecret()).isEqualTo("1e5");
    assertThat(alpha.apaleoPropertyIds()).containsExactly("BER", "007");
    assertThat(connections.bySecret("0042")).isPresent();

    Files.writeString(file, "connections:\n  beta:\n    tenantLabel: globex\n");
    assertThatThrownBy(() -> Connections.from(file))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("beta lacks");
  }

  @Test
  @DisplayName("no secret appears in a connection's string form")
  void secretsAreMasked() {
    String text = connection("alpha", "a-secret").toString();

    assertThat(text)
        .contains("alpha")
        .doesNotContain("a-secret")
        .doesNotContain("key-alpha")
        .doesNotContain("s3cret");
  }

  @Test
  @DisplayName("a missing file is named in quotes, so a stray space in the path shows")
  void missingFileIsQuoted() {
    assertThatThrownBy(() -> Connections.from(Path.of("/nowhere/connections.yaml ")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("'/nowhere/connections.yaml '");
  }
}
