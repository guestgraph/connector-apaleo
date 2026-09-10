package io.guestgraph.connector.apaleo.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The recorded Apaleo documents under {@code src/test/resources/apaleo}, read from the classpath.
 */
public final class Recorded {

  private Recorded() {}

  public static String document(String name) {
    try (InputStream in = Recorded.class.getResourceAsStream("/apaleo/" + name)) {
      if (in == null) {
        throw new IllegalArgumentException("No recorded document " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
