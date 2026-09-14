package io.guestgraph.connector.apaleo.api.ops;

import io.guestgraph.connector.apaleo.api.ApaleoUnreachableException;
import io.guestgraph.connector.apaleo.api.NoSuchConnectionException;
import io.guestgraph.connector.apaleo.api.NoSuchRunException;
import io.guestgraph.connector.apaleo.api.events.Subscriptions;
import io.guestgraph.connector.apaleo.api.ops.StatusDocuments.ConnectionStatus;
import io.guestgraph.connector.apaleo.api.ops.StatusDocuments.Counters;
import io.guestgraph.connector.apaleo.api.ops.StatusDocuments.Run;
import io.guestgraph.connector.apaleo.api.ops.StatusDocuments.RunStarted;
import io.guestgraph.connector.apaleo.api.ops.StatusDocuments.Status;
import io.guestgraph.connector.apaleo.api.ops.StatusDocuments.Subscription;
import io.guestgraph.connector.apaleo.api.ops.StatusDocuments.SubscriptionRemoved;
import io.guestgraph.connector.apaleo.api.ops.StatusDocuments.SubscriptionState;
import io.guestgraph.connector.apaleo.api.ops.StatusDocuments.SyncPoint;
import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.Connections;
import io.guestgraph.connector.apaleo.persistence.entity.ConnectionEntity;
import io.guestgraph.connector.apaleo.persistence.entity.SyncRunEntity;
import io.guestgraph.connector.apaleo.persistence.repo.ConnectionRepo;
import io.guestgraph.connector.apaleo.persistence.repo.HeldGuestIdRepo;
import io.guestgraph.connector.apaleo.persistence.repo.ProcessedEventRepo;
import io.guestgraph.connector.apaleo.persistence.repo.SyncPointRepo;
import io.guestgraph.connector.apaleo.persistence.repo.SyncRunRepo;
import io.guestgraph.connector.apaleo.sync.FullSync;
import io.guestgraph.connector.apaleo.sync.Reconciliation;
import io.guestgraph.connector.apaleo.sync.Refresh;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operations surface of research R9: the status, one document for every configured connection,
 * and the runs an operator starts and reads. Behind the shared bearer token filter; a refusal is a
 * problem of the family's shape, thrown here and written by the shared advice.
 */
@RestController
public class StatusController {

  private final Connections connections;
  private final ConnectionRepo connectionRows;
  private final SyncPointRepo syncPoints;
  private final SyncRunRepo syncRuns;
  private final ProcessedEventRepo events;
  private final HeldGuestIdRepo heldGuestIds;
  private final Subscriptions subscriptions;
  private final LastErrors lastErrors;
  private final FullSync fullSync;
  private final Reconciliation reconciliation;
  private final Refresh refresh;

  public StatusController(
      Connections connections,
      ConnectionRepo connectionRows,
      SyncPointRepo syncPoints,
      SyncRunRepo syncRuns,
      ProcessedEventRepo events,
      HeldGuestIdRepo heldGuestIds,
      Subscriptions subscriptions,
      LastErrors lastErrors,
      FullSync fullSync,
      Reconciliation reconciliation,
      Refresh refresh) {
    this.connections = connections;
    this.connectionRows = connectionRows;
    this.syncPoints = syncPoints;
    this.syncRuns = syncRuns;
    this.events = events;
    this.heldGuestIds = heldGuestIds;
    this.subscriptions = subscriptions;
    this.lastErrors = lastErrors;
    this.fullSync = fullSync;
    this.reconciliation = reconciliation;
    this.refresh = refresh;
  }

  @GetMapping("/status")
  @Transactional(readOnly = true)
  public Status status() {
    List<ConnectionStatus> statuses = new ArrayList<>();
    for (ConnectionConfig c : connections.all()) {
      statuses.add(status(c));
    }
    return new Status(statuses);
  }

  @PostMapping("/connections/{connectionId}/sync/full")
  public ResponseEntity<RunStarted> startFullSync(@PathVariable String connectionId) {
    return started(fullSync.start(connection(connectionId)));
  }

  @PostMapping("/connections/{connectionId}/sync/reconcile")
  public ResponseEntity<RunStarted> startReconciliation(@PathVariable String connectionId) {
    return started(reconciliation.start(connection(connectionId)));
  }

  @PostMapping("/connections/{connectionId}/refresh")
  public ResponseEntity<RunStarted> startRefresh(@PathVariable String connectionId) {
    return started(refresh.start(connection(connectionId)));
  }

  @GetMapping("/connections/{connectionId}/runs/{runId}")
  @Transactional(readOnly = true)
  public Run run(@PathVariable String connectionId, @PathVariable String runId) {
    ConnectionConfig c = connection(connectionId);
    UUID id;
    try {
      id = UUID.fromString(runId);
    } catch (IllegalArgumentException e) {
      // The contract knows 404 only: an id that is no run id names no run.
      throw new NoSuchRunException();
    }
    SyncRunEntity run = syncRuns.find(c.name(), id).orElseThrow(NoSuchRunException::new);
    return new Run(
        run.getId(),
        run.getKind(),
        run.getStartedAt(),
        run.getFinishedAt(),
        run.getOutcome(),
        run.getReservationsSeen(),
        run.getVersionsSubmitted(),
        run.getRecordsSubmitted(),
        run.getDuplicates(),
        run.getFlaggedForReview(),
        run.getErrors(),
        run.getLastError());
  }

  /**
   * Takes a connection's subscription away in Apaleo, before a teardown or a tunnel goes down (spec
   * 009). A statement of the end state: asking twice succeeds, and {@code removed} says whether
   * there was one to delete. The absence lasts until a restore or a restart.
   */
  @DeleteMapping("/connections/{connectionId}/subscription")
  public SubscriptionRemoved removeSubscription(@PathVariable String connectionId) {
    ConnectionConfig c = connection(connectionId);
    Subscriptions.Removal removal;
    try {
      removal = subscriptions.remove(c);
    } catch (RuntimeException e) {
      throw new ApaleoUnreachableException("the subscription was not removed: " + e.getMessage());
    }
    Subscriptions.Status status = removal.status();
    return new SubscriptionRemoved(
        status.state().name(),
        status.id(),
        status.eventTypes(),
        status.checkedAt(),
        removal.removed(),
        removal.endpoint());
  }

  /**
   * Puts a removed subscription back, the act the connector performs for every connection when it
   * starts. The way back from a removal without editing configuration or restarting (spec 009).
   */
  @PutMapping("/connections/{connectionId}/subscription")
  public SubscriptionState restoreSubscription(@PathVariable String connectionId) {
    ConnectionConfig c = connection(connectionId);
    Subscriptions.Status status = subscriptions.restore(c);
    if (status.state() != Subscriptions.State.ACTIVE) {
      throw new ApaleoUnreachableException("the subscription was not created: " + status.reason());
    }
    return new SubscriptionState(
        status.state().name(), status.id(), status.eventTypes(), status.checkedAt());
  }

  private ConnectionConfig connection(String connectionId) {
    return connections.byName(connectionId).orElseThrow(NoSuchConnectionException::new);
  }

  private static ResponseEntity<RunStarted> started(UUID runId) {
    return ResponseEntity.accepted().body(new RunStarted(runId));
  }

  private ConnectionStatus status(ConnectionConfig c) {
    ConnectionEntity row = connectionRows.find(c.name()).orElse(null);
    Subscriptions.Status subscription = subscriptions.status(c);
    List<SyncPoint> points =
        syncPoints.findAll(c.name()).stream()
            .map(
                p ->
                    new SyncPoint(
                        p.getKey().propertyId(),
                        p.getModifiedThrough(),
                        p.getLastFullSyncAt(),
                        p.getLastReconcileAt()))
            .toList();
    return new ConnectionStatus(
        c.name(),
        c.tenantLabel(),
        c.apaleoAccount(),
        c.apaleoPropertyIds(),
        new Subscription(
            subscription.active(),
            subscription.state().name(),
            subscription.id(),
            subscription.eventTypes()),
        points,
        row == null ? null : row.getLastActivityAt(),
        row == null
            ? new Counters(0, 0, 0, 0, 0)
            : new Counters(
                row.getVersionsSubmitted(),
                row.getRecordsSubmitted(),
                row.getDuplicates(),
                row.getFlaggedForReview(),
                row.getErrors()),
        events.countPending(c.name()),
        heldGuestIds.countAwaitingPerson(c.name()),
        // When the ids were last read, whether or not one of them could not be: the run's
        // outcome and its errors say that.
        syncRuns.lastFinished(c.name(), "REFRESH").map(SyncRunEntity::getFinishedAt).orElse(null),
        lastErrors.find(c.name()).orElse(null));
  }
}
