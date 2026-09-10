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

  public ApaleoClients(ConnectorProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public ApaleoClient forConnection(ConnectionConfig c) {
    return clients.computeIfAbsent(c.name(), name -> build(c));
  }

  /** A client that answers every status, so the connector decides what a 429 or a 401 means. */
  private static RestClient.Builder lenient() {
    return Http.client().defaultStatusHandler(status -> true, (request, response) -> {});
  }

  private ApaleoClient build(ConnectionConfig c) {
    ApaleoAuth auth =
        new ApaleoAuth(
            lenient().baseUrl(properties.apaleo().identityUrl()).build(),
            c.apaleoClientId(),
            c.apaleoClientSecret(),
            clock);
    return new ApaleoClient(
        lenient().baseUrl(properties.apaleo().apiUrl()).build(),
        auth,
        Sleeper.real(),
        properties.apaleo().backoffInitial(),
        properties.apaleo().backoffMax());
  }
}
