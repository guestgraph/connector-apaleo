package io.guestgraph.connector.apaleo.apaleo;

/** Apaleo answered something the client cannot proceed on; the status carries the reason. */
public class ApaleoException extends RuntimeException {

  private final int status;

  public ApaleoException(String what, int status) {
    super(what + ": Apaleo answered " + status);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
