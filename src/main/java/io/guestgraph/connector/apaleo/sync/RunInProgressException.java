package io.guestgraph.connector.apaleo.sync;

import io.guestgraph.service.ServiceException;
import org.springframework.http.HttpStatus;

/** A run of this kind is already in progress on the connection; the request answers 409. */
public class RunInProgressException extends ServiceException {

  public RunInProgressException(String connection, String kind) {
    super(
        HttpStatus.CONFLICT,
        "run-in-progress",
        "Run in progress",
        "A " + kind + " run is already in progress on connection " + connection);
  }
}
