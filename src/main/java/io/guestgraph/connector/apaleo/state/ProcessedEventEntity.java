package io.guestgraph.connector.apaleo.state;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/** A webhook delivery, by Apaleo's event id, so the second delivery is a no-op. */
@Entity
@Table(name = "processed_event")
public class ProcessedEventEntity {

  @EmbeddedId private EventKey key;
  private String eventType;
  private String objectType;
  private String objectId;
  private String propertyId;
  private Instant receivedAt;
  private String state;
  private int attempts;
  private Instant nextAttemptAt;
  private String lastError;

  protected ProcessedEventEntity() {}

  public EventKey getKey() {
    return key;
  }

  public String getEventType() {
    return eventType;
  }

  public String getObjectType() {
    return objectType;
  }

  public String getObjectId() {
    return objectId;
  }

  public String getPropertyId() {
    return propertyId;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public String getState() {
    return state;
  }

  public int getAttempts() {
    return attempts;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public String getLastError() {
    return lastError;
  }
}
