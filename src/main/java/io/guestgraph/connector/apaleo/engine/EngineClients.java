package io.guestgraph.connector.apaleo.engine;

import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Http;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** One engine client per connection, with that connection's base URL and key (research R12). */
@Component
public class EngineClients {

  private final Map<String, EngineClient> clients = new ConcurrentHashMap<>();

  public EngineClient forConnection(ConnectionConfig c) {
    return clients.computeIfAbsent(c.name(), name -> build(c));
  }

  static EngineClient build(ConnectionConfig c) {
    return new EngineClient(
        Http.client()
            .baseUrl(c.engineBaseUrl())
            .defaultHeader("X-API-Key", c.engineApiKey())
            .defaultStatusHandler(status -> true, (request, response) -> {})
            .build());
  }
}
