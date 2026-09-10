package io.guestgraph.connector.apaleo.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ConnectorProperties.class)
public class ConnectorConfig {

  /** One clock, so runs and tests agree on now. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /** A run writes one transaction per object, never one long transaction across the run. */
  @Bean
  public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
    return new TransactionTemplate(transactionManager);
  }
}
