package io.guestgraph.connector.apaleo.apaleo;

import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.ConnectorProperties;
import io.guestgraph.connector.apaleo.config.Http;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** One Apaleo client per connection, built once and kept (research R12). */
@Component
public class ApaleoClients {

  private final ConnectorProperties properties;
  private final Clock clock;
  private final Map<String, ApaleoClient> clients = new ConcurrentHashMap<>();
  private final Map<String, ApaleoWebhooks> webhooks = new ConcurrentHashMap<>();
  private final Map<String, ApaleoAuth> auths = new ConcurrentHashMap<>();

  public ApaleoClients(ConnectorProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public ApaleoClient forConnection(ConnectionConfig c) {
    return clients.computeIfAbsent(c.name(), name -> build(c));
  }

  /** The same connection's client for the webhook API, on the same token. */
  public ApaleoWebhooks webhooksFor(ConnectionConfig c) {
    return webhooks.computeIfAbsent(
        c.name(),
        name ->
            new ApaleoWebhooks(
                lenient().baseUrl(properties.apaleo().webhookUrl()).build(), auth(c)));
  }

  /** A client that answers every status, so the connector decides what a 429 or a 401 means. */
  private static RestClient.Builder lenient() {
    return Http.client().defaultStatusHandler(status -> true, (request, response) -> {});
  }

  private ApaleoAuth auth(ConnectionConfig c) {
    return auths.computeIfAbsent(
        c.name(),
        name ->
            new ApaleoAuth(
                lenient().baseUrl(properties.apaleo().identityUrl()).build(),
                c.apaleoClientId(),
                c.apaleoClientSecret(),
                clock));
  }

  private ApaleoClient build(ConnectionConfig c) {
    return new ApaleoClient(
        lenient().baseUrl(properties.apaleo().apiUrl()).build(),
        auth(c),
        Sleeper.real(),
        properties.apaleo().backoffInitial(),
        properties.apaleo().backoffMax());
  }
}
