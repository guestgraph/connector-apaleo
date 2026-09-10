package io.guestgraph.connector.apaleo.engine;

/** The engine answered something the connector cannot proceed on. */
public class EngineException extends RuntimeException {

  private final int status;

  public EngineException(String what, int status) {
    super(what + ": the engine answered " + status);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
