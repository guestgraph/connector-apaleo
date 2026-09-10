package io.guestgraph.connector.apaleo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
  @DisplayName("no secret appears in a connection's string form")
  void secretsAreMasked() {
    String text = connection("alpha", "a-secret").toString();

    assertThat(text)
        .contains("alpha")
        .doesNotContain("a-secret")
        .doesNotContain("key-alpha")
        .doesNotContain("s3cret");
  }
}
