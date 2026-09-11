package io.guestgraph.connector.apaleo.api;

import io.guestgraph.service.ServiceException;
import org.springframework.http.HttpStatus;

/** No configured connection carries that name; the answer is the contract's 404. */
public class NoSuchConnectionException extends ServiceException {
  public NoSuchConnectionException() {
    super(HttpStatus.NOT_FOUND, "not-found", "Resource not found", "no such connection");
  }
}
