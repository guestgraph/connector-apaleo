package io.guestgraph.connector.apaleo.api;

import io.guestgraph.service.ServiceException;
import org.springframework.http.HttpStatus;

/**
 * Apaleo refused or could not be reached, so what the operator asked for did not happen. A
 * gateway's failure rather than the caller's, and deliberately distinct from a refusal the
 * connector decided: the request was well formed and may succeed when Apaleo answers again. The
 * detail names neither credential nor secret.
 */
public class ApaleoUnreachableException extends ServiceException {
  public ApaleoUnreachableException(String detail) {
    super(HttpStatus.BAD_GATEWAY, "apaleo-unreachable", "Apaleo could not be reached", detail);
  }
}
