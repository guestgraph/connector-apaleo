package io.guestgraph.connector.apaleo.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The instance's configuration (data-model "Configuration", per instance). Secrets never appear in
 * {@link #toString()}; the connections and their secrets live in the file {@link #connectionsFile}
 * names and are loaded by {@link Connections}.
 */
@ConfigurationProperties(prefix = "connector")
public record ConnectorProperties(
    String publicUrl,
    String opsToken,
    Path connectionsFile,
    String engineSourceSystem,
    Apaleo apaleo,
    Reconcile reconcile,
    Duration resyncAfterGap,
    String refreshCron) {

  public record Apaleo(
      String identityUrl,
      String apiUrl,
      String webhookUrl,
      List<String> eventTypes,
      Duration backoffInitial,
      Duration backoffMax) {}

  public record Reconcile(Duration interval, Duration overlap) {}

  @Override
  public String toString() {
    return "ConnectorProperties[publicUrl="
        + publicUrl
        + ", opsToken=****, connectionsFile="
        + connectionsFile
        + ", engineSourceSystem="
        + engineSourceSystem
        + ", apaleo="
        + apaleo
        + ", reconcile="
        + reconcile
        + ", resyncAfterGap="
        + resyncAfterGap
        + ", refreshCron="
        + refreshCron
        + "]";
  }
}
