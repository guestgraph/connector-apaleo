package io.guestgraph.connector.apaleo.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One full sync, reconciliation or refresh of one connection, for the status. */
@Entity
@Table(name = "sync_run")
public class SyncRunEntity {

  @Id private UUID id;
  private String connectionId;
  private String kind;
  private Instant startedAt;
  private Instant finishedAt;
  private String outcome;
  private int reservationsSeen;
  private int versionsSubmitted;
  private int recordsSubmitted;
  private int duplicates;
  private int flaggedForReview;
  private int errors;
  private String lastError;

  protected SyncRunEntity() {}

  public UUID getId() {
    return id;
  }

  public String getConnectionId() {
    return connectionId;
  }

  public String getKind() {
    return kind;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }

  public String getOutcome() {
    return outcome;
  }

  public int getReservationsSeen() {
    return reservationsSeen;
  }

  public int getVersionsSubmitted() {
    return versionsSubmitted;
  }

  public int getRecordsSubmitted() {
    return recordsSubmitted;
  }

  public int getDuplicates() {
    return duplicates;
  }

  public int getFlaggedForReview() {
    return flaggedForReview;
  }

  public int getErrors() {
    return errors;
  }

  public String getLastError() {
    return lastError;
  }
}
