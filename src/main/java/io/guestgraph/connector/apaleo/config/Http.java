package io.guestgraph.connector.apaleo.config;

import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Every outbound client the connector builds. HTTP/1.1 on purpose: both upstreams speak it, and the
 * JDK client's h2c upgrade on plain HTTP is what a stub server resets when a body streams.
 */
public final class Http {

  private Http() {}

  public static RestClient.Builder client() {
    return RestClient.builder()
        .requestFactory(
            new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()));
  }
}
