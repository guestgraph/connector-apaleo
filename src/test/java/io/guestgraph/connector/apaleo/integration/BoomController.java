package io.guestgraph.connector.apaleo.integration;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A planted failure for the error-shape test; test sources only. */
@RestController
public class BoomController {

  @GetMapping("/test/boom")
  public String boom() {
    throw new IllegalStateException("the cause, which must never reach a caller");
  }
}
