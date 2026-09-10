package io.guestgraph.connector.apaleo.persistence.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The guest the engine answered for one slot, and what the last refresh said about it. */
@Entity
@Table(name = "held_guest_id")
public class HeldGuestIdEntity {

  @EmbeddedId private HeldGuestKey key;
  private UUID guestId;
  private UUID sourceRecordId;
  private String resolutionStatus;

  @JdbcTypeCode(SqlTypes.JSON)
  private List<UUID> currentGuestIds;

  private Instant refreshedAt;

  protected HeldGuestIdEntity() {}

  public HeldGuestKey getKey() {
    return key;
  }

  public UUID getGuestId() {
    return guestId;
  }

  public UUID getSourceRecordId() {
    return sourceRecordId;
  }

  public String getResolutionStatus() {
    return resolutionStatus;
  }

  public List<UUID> getCurrentGuestIds() {
    return currentGuestIds;
  }

  public Instant getRefreshedAt() {
    return refreshedAt;
  }
}
