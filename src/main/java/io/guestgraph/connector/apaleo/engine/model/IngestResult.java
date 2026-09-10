package io.guestgraph.connector.apaleo.engine.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The engine's answer per record; {@code status} is the resolution outcome. */
public record IngestResult(
    String externalKey,
    UUID sourceRecordId,
    UUID guestId,
    String status,
    boolean needsReview,
    List<UUID> pendingReviewIds,
    Map<String, Object> problem) {

  public boolean failed() {
    return "ERROR".equals(status) || guestId == null;
  }

  public boolean duplicate() {
    return "DUPLICATE_IGNORED".equals(status);
  }
}
