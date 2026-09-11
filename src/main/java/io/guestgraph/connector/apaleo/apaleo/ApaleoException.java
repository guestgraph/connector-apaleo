package io.guestgraph.connector.apaleo.apaleo;

/** Apaleo answered something the client cannot proceed on; the status carries the reason. */
public class ApaleoException extends RuntimeException {

  private final int status;

  public ApaleoException(String what, int status) {
    super(message(what, status));
    this.status = status;
  }

  /** "what: Apaleo answered 400 (invalid_scope)": a code after the status reads as its reason. */
  private static String message(String what, int status) {
    int code = what.indexOf(" (");
    return code < 0
        ? what + ": Apaleo answered " + status
        : what.substring(0, code) + ": Apaleo answered " + status + what.substring(code);
  }

  public int status() {
    return status;
  }
}
