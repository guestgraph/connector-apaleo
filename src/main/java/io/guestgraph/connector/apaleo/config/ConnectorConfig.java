package io.guestgraph.connector.apaleo.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ConnectorProperties.class)
public class ConnectorConfig {

  /** One clock, so runs and tests agree on now. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
