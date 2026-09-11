package io.guestgraph.connector.apaleo.api;

import io.guestgraph.service.ServiceException;
import org.springframework.http.HttpStatus;

/** The connection has no run of that id; an id that is no run id names no run either. */
public class NoSuchRunException extends ServiceException {
  public NoSuchRunException() {
    super(HttpStatus.NOT_FOUND, "not-found", "Resource not found", "no such run");
  }
}
