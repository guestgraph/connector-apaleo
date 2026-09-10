package io.guestgraph.connector.apaleo.state;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/** The last version submitted for one object, and the roster hash that decides the next. */
@Entity
@Table(name = "object_state")
public class ObjectStateEntity {

  @EmbeddedId private ObjectKey key;
  private String propertyId;
  private String bookingId;
  private Instant lastModified;
  private String rosterHash;
  private Instant lastSubmittedAt;
  private String lastStatus;

  protected ObjectStateEntity() {}

  public ObjectKey getKey() {
    return key;
  }

  public String getPropertyId() {
    return propertyId;
  }

  public String getBookingId() {
    return bookingId;
  }

  public Instant getLastModified() {
    return lastModified;
  }

  public String getRosterHash() {
    return rosterHash;
  }

  public Instant getLastSubmittedAt() {
    return lastSubmittedAt;
  }

  public String getLastStatus() {
    return lastStatus;
  }
}
