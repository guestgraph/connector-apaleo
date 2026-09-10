package io.guestgraph.connector.apaleo.apaleo;

import java.time.Duration;

/** Waiting, as a seam: production sleeps, tests record. */
@FunctionalInterface
public interface Sleeper {

  void sleep(Duration duration) throws InterruptedException;

  static Sleeper real() {
    return duration -> Thread.sleep(duration.toMillis());
  }
}
