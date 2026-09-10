package io.guestgraph.connector.apaleo.apaleo;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * One connection's client-credentials token (research R2): fetched with the connection's Basic
 * credential, cached, and refreshed a minute before Apaleo's 3,600-second expiry.
 */
public class ApaleoAuth {

  static final Duration REFRESH_AHEAD = Duration.ofMinutes(1);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final RestClient identity;
  private final String basicCredential;
  private final Clock clock;
  private String token;
  private Instant expiresAt = Instant.MIN;

  public ApaleoAuth(RestClient identity, String clientId, String clientSecret, Clock clock) {
    this.identity = identity;
    this.basicCredential =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
    this.clock = clock;
  }

  public synchronized String token() {
    if (token == null || !clock.instant().isBefore(expiresAt.minus(REFRESH_AHEAD))) {
      refresh();
    }
    return token;
  }

  private void refresh() {
    ResponseEntity<String> response =
        identity
            .post()
            .uri("/connect/token")
            .header("Authorization", basicCredential)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("grant_type=client_credentials&scope=reservations.read")
            .retrieve()
            .toEntity(String.class);
    if (response.getStatusCode().isError() || response.getBody() == null) {
      throw new ApaleoException("token", response.getStatusCode().value());
    }
    Map<?, ?> answer;
    try {
      answer = JSON.readValue(response.getBody(), Map.class);
    } catch (RuntimeException e) {
      throw new ApaleoException("token body is not JSON", response.getStatusCode().value());
    }
    if (answer.get("access_token") == null) {
      throw new ApaleoException("token without access_token", response.getStatusCode().value());
    }
    token = String.valueOf(answer.get("access_token"));
    Object expiresIn = answer.get("expires_in");
    long seconds = expiresIn instanceof Number n ? n.longValue() : 3600;
    expiresAt = clock.instant().plusSeconds(seconds);
  }
}
