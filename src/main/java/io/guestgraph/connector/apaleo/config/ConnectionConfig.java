package io.guestgraph.connector.apaleo.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * One connection: an engine tenant with its key paired with an Apaleo account with its credential
 * and properties (spec FR-015). The secrets are held here and nowhere else — {@link #toString()}
 * masks them, and only the webhook secret's hash reaches the database.
 */
public record ConnectionConfig(
    String name,
    String tenantLabel,
    String engineBaseUrl,
    String engineApiKey,
    String apaleoAccount,
    String apaleoClientId,
    String apaleoClientSecret,
    List<String> apaleoPropertyIds,
    String webhookSecret) {

  public ConnectionConfig {
    apaleoPropertyIds = apaleoPropertyIds == null ? List.of() : List.copyOf(apaleoPropertyIds);
  }

  /** What routes a delivery: the hash, so the secret itself is never stored. */
  public String webhookSecretHash() {
    return sha256(webhookSecret);
  }

  static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public String toString() {
    return "ConnectionConfig[name="
        + name
        + ", tenantLabel="
        + tenantLabel
        + ", engineBaseUrl="
        + engineBaseUrl
        + ", engineApiKey=****, apaleoAccount="
        + apaleoAccount
        + ", apaleoClientId="
        + apaleoClientId
        + ", apaleoClientSecret=****, apaleoPropertyIds="
        + apaleoPropertyIds
        + ", webhookSecret=****]";
  }
}
