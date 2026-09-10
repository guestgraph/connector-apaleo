package io.guestgraph.connector.apaleo.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@EnableConfigurationProperties(ConnectorProperties.class)
public class ConnectorConfig {

  /** One clock, so runs and tests agree on now. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /** Runs write in transactions of their own, one object at a time, not one long transaction. */
  @Bean
  public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
    return new TransactionTemplate(transactionManager);
  }
}
