package io.guestgraph.connector.apaleo.sync;

/** A run of this kind is already in progress on the connection; the request answers 409. */
public class RunInProgressException extends RuntimeException {

  public RunInProgressException(String connection, String kind) {
    super("A " + kind + " run is already in progress on connection " + connection);
  }
}
