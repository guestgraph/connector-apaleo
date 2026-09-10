package io.guestgraph.connector.apaleo.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The instance's configuration (data-model "Configuration", per instance). Secrets never appear in
 * {@link #toString()}; the connections and their secrets live in the file {@link #connectionsFile}
 * names and are loaded by {@link Connections}.
 */
@Validated
@ConfigurationProperties(prefix = "connector")
public record ConnectorProperties(
    @NotBlank String publicUrl,
    @NotBlank String opsToken,
    @NotNull Path connectionsFile,
    String engineSourceSystem,
    Apaleo apaleo,
    Reconcile reconcile,
    Events events,
    Duration resyncAfterGap,
    String refreshCron,
    Boolean syncOnBoot) {

  public record Apaleo(
      String identityUrl,
      String apiUrl,
      String webhookUrl,
      List<String> eventTypes,
      Duration backoffInitial,
      Duration backoffMax) {}

  public record Reconcile(Duration interval, Duration overlap) {}

  public record Events(Duration pollInterval) {}

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
        + ", events="
        + events
        + ", resyncAfterGap="
        + resyncAfterGap
        + ", refreshCron="
        + refreshCron
        + ", syncOnBoot="
        + syncOnBoot
        + "]";
  }
}
