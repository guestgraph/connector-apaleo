package io.guestgraph.connector.apaleo.engine.model;

import java.util.List;
import java.util.UUID;

/**
 * What a guest id refers to now (slice 4 of the engine): ACTIVE, or MERGED to one current id, SPLIT
 * to several, RETIRED to none. An active guest answers with its document; only the status is read
 * from it.
 */
public record GuestResolution(String status, List<UUID> currentGuestIds) {

  public boolean active() {
    return "ACTIVE".equals(status);
  }
}
