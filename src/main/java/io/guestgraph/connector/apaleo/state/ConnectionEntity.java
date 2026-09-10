package io.guestgraph.connector.apaleo.state;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One configured connection, written from configuration at start; the secrets never enter it, only
 * the webhook secret's hash, which routes a delivery.
 */
@Entity
@Table(name = "connection")
public class ConnectionEntity {

  @Id private String id;
  private String tenantLabel;
  private String apaleoAccount;

  @JdbcTypeCode(SqlTypes.JSON)
  private List<String> propertyIds;

  private String webhookSecretHash;
  private Instant createdAt;
  private Instant lastActivityAt;

  protected ConnectionEntity() {}

  public ConnectionEntity(
      String id,
      String tenantLabel,
      String apaleoAccount,
      List<String> propertyIds,
      String webhookSecretHash,
      Instant createdAt,
      Instant lastActivityAt) {
    this.id = id;
    this.tenantLabel = tenantLabel;
    this.apaleoAccount = apaleoAccount;
    this.propertyIds = List.copyOf(propertyIds);
    this.webhookSecretHash = webhookSecretHash;
    this.createdAt = createdAt;
    this.lastActivityAt = lastActivityAt;
  }

  public String getId() {
    return id;
  }

  public String getTenantLabel() {
    return tenantLabel;
  }

  public String getApaleoAccount() {
    return apaleoAccount;
  }

  public List<String> getPropertyIds() {
    return propertyIds;
  }

  public String getWebhookSecretHash() {
    return webhookSecretHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getLastActivityAt() {
    return lastActivityAt;
  }
}
