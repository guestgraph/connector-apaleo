package io.guestgraph.connector.apaleo.engine;

/** The engine answered something the connector cannot proceed on. */
public class EngineException extends RuntimeException {

  private final int status;

  public EngineException(String what, int status) {
    super(what + ": the engine answered " + status);
    this.status = status;
  }

  /** No answer at all; the cause says why, in the client's words, which name no credential. */
  public EngineException(String what, Throwable cause) {
    super(what + ": " + cause.getMessage(), cause);
    this.status = 0;
  }

  public int status() {
    return status;
  }
}
