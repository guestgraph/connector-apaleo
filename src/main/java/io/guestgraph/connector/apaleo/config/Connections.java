package io.guestgraph.connector.apaleo.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * The configured connections, read once from the file {@code connector.connections-file} names
 * (spec FR-015a). The file is refused at start when a connection lacks a field, or when two share a
 * name or a webhook secret, because the secret is what routes a delivery.
 */
@Component
public class Connections {

  private final Map<String, ConnectionConfig> byName;
  private final Map<String, ConnectionConfig> bySecretHash;

  @Autowired
  public Connections(ConnectorProperties properties) {
    this(load(properties.connectionsFile()));
  }

  Connections(List<ConnectionConfig> connections) {
    Map<String, ConnectionConfig> names = new LinkedHashMap<>();
    Map<String, ConnectionConfig> hashes = new LinkedHashMap<>();
    for (ConnectionConfig c : connections) {
      if (names.put(c.name(), c) != null) {
        throw new IllegalStateException("Two connections are named " + c.name());
      }
      if (hashes.put(c.webhookSecretHash(), c) != null) {
        throw new IllegalStateException(
            "Connection " + c.name() + " shares its webhook secret with another connection");
      }
    }
    this.byName = Map.copyOf(names);
    this.bySecretHash = Map.copyOf(hashes);
  }

  public List<ConnectionConfig> all() {
    return List.copyOf(byName.values());
  }

  public Optional<ConnectionConfig> byName(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  /** The connection a delivery belongs to, by the secret in its path; compared in constant time. */
  public Optional<ConnectionConfig> bySecret(String secret) {
    byte[] given = ConnectionConfig.sha256(secret).getBytes(StandardCharsets.UTF_8);
    for (Map.Entry<String, ConnectionConfig> entry : bySecretHash.entrySet()) {
      if (MessageDigest.isEqual(entry.getKey().getBytes(StandardCharsets.UTF_8), given)) {
        return Optional.of(entry.getValue());
      }
    }
    return Optional.empty();
  }

  private static List<ConnectionConfig> load(java.nio.file.Path file) {
    if (file == null) {
      throw new IllegalStateException("connector.connections-file is not set");
    }
    Map<String, Object> root;
    try (InputStream in = Files.newInputStream(file)) {
      root = new Yaml().load(in);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read connections file " + file, e);
    }
    Object entries = root == null ? null : root.get("connections");
    if (!(entries instanceof Map<?, ?> map) || map.isEmpty()) {
      throw new IllegalStateException("Connections file " + file + " lists no connections");
    }
    List<ConnectionConfig> connections = new ArrayList<>();
    Set<String> required =
        new HashSet<>(
            List.of(
                "tenantLabel",
                "engineBaseUrl",
                "engineApiKey",
                "apaleoAccount",
                "apaleoClientId",
                "apaleoClientSecret",
                "webhookSecret"));
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String name = String.valueOf(entry.getKey());
      if (!(entry.getValue() instanceof Map<?, ?> fields)) {
        throw new IllegalStateException("Connection " + name + " is not a mapping");
      }
      for (String key : required) {
        Object value = fields.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
          throw new IllegalStateException("Connection " + name + " lacks " + key);
        }
      }
      List<String> propertyIds = new ArrayList<>();
      if (fields.get("apaleoPropertyIds") instanceof List<?> ids) {
        ids.forEach(id -> propertyIds.add(String.valueOf(id)));
      }
      connections.add(
          new ConnectionConfig(
              name,
              text(fields, "tenantLabel"),
              text(fields, "engineBaseUrl"),
              text(fields, "engineApiKey"),
              text(fields, "apaleoAccount"),
              text(fields, "apaleoClientId"),
              text(fields, "apaleoClientSecret"),
              propertyIds,
              text(fields, "webhookSecret")));
    }
    return connections;
  }

  private static String text(Map<?, ?> fields, String key) {
    return String.valueOf(fields.get(key)).trim();
  }
}
