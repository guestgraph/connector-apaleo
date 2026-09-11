package io.guestgraph.connector.apaleo.api;

import io.guestgraph.service.ServiceException;
import org.springframework.http.HttpStatus;

/** A body the webhook endpoint cannot read as an Apaleo delivery. */
public class NotAnEventException extends ServiceException {
  public NotAnEventException() {
    super(
        HttpStatus.BAD_REQUEST,
        "invalid-request",
        "Invalid request",
        "not an Apaleo event: id, topic and data.entityId are required");
  }
}
