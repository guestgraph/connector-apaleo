package io.guestgraph.connector.apaleo.api.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventWorkerTest {

  @Test
  @DisplayName("the wait doubles from ten seconds and stops at an hour")
  void backoffDoublesToAnHour() {
    assertThat(EventWorker.backoff(1)).isEqualTo(Duration.ofSeconds(10));
    assertThat(EventWorker.backoff(2)).isEqualTo(Duration.ofSeconds(20));
    assertThat(EventWorker.backoff(5)).isEqualTo(Duration.ofSeconds(160));
    assertThat(EventWorker.backoff(10)).isEqualTo(Duration.ofHours(1));
    assertThat(EventWorker.backoff(40)).isEqualTo(Duration.ofHours(1));
  }
}
