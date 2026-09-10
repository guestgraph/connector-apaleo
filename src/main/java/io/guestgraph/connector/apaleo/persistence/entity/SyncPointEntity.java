package io.guestgraph.connector.apaleo.persistence.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/** How far one property of one connection has been submitted. */
@Entity
@Table(name = "sync_point")
public class SyncPointEntity {

  @EmbeddedId private SyncPointKey key;
  private Instant modifiedThrough;
  private Instant lastFullSyncAt;
  private Instant lastReconcileAt;

  protected SyncPointEntity() {}

  public SyncPointKey getKey() {
    return key;
  }

  public Instant getModifiedThrough() {
    return modifiedThrough;
  }

  public Instant getLastFullSyncAt() {
    return lastFullSyncAt;
  }

  public Instant getLastReconcileAt() {
    return lastReconcileAt;
  }
}
