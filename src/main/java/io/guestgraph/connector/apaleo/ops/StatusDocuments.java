package io.guestgraph.connector.apaleo.ops;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The documents of the operations contract, {@code connector-api.yaml}. */
public final class StatusDocuments {

  private StatusDocuments() {}

  public record Status(List<ConnectionStatus> connections) {}

  public record ConnectionStatus(
      String id,
      String tenantLabel,
      String account,
      List<String> properties,
      Subscription subscription,
      List<SyncPoint> syncPoints,
      Instant lastActivityAt,
      Counters counters,
      long pendingEvents,
      long splitsAwaitingPerson,
      Instant lastRefreshAt,
      LastErrors.LastError lastError) {}

  public record Subscription(boolean active, String id, List<String> eventTypes) {}

  public record SyncPoint(
      String propertyId,
      Instant modifiedThrough,
      Instant lastFullSyncAt,
      Instant lastReconcileAt) {}

  public record Counters(
      long versionsSubmitted,
      long recordsSubmitted,
      long duplicates,
      long flaggedForReview,
      long errors) {}

  public record RunStarted(UUID runId) {}

  public record Run(
      UUID id,
      String kind,
      Instant startedAt,
      Instant finishedAt,
      String outcome,
      int reservationsSeen,
      int versionsSubmitted,
      int recordsSubmitted,
      int duplicates,
      int flaggedForReview,
      int errors,
      String lastError) {}
}
