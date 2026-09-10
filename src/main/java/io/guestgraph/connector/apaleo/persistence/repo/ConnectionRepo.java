package io.guestgraph.connector.apaleo.persistence.repo;

import io.guestgraph.connector.apaleo.persistence.entity.ConnectionEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ConnectionRepo extends Repository<ConnectionEntity, String> {

  @Query("select c from ConnectionEntity c where c.id = :connectionId")
  Optional<ConnectionEntity> find(@Param("connectionId") String connectionId);

  @ConnectionAgnostic("the status lists every connection")
  @Query("select c from ConnectionEntity c order by c.id")
  List<ConnectionEntity> findAll();

  @ConnectionAgnostic("a delivery is routed to its connection by the secret in its path")
  @Query("select c from ConnectionEntity c where c.webhookSecretHash = :secretHash")
  Optional<ConnectionEntity> findBySecretHash(@Param("secretHash") String secretHash);

  /** Native: jsonb cast, and an upsert keeps configuration re-reads idempotent. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      nativeQuery = true,
      value =
          """
            INSERT INTO connection (id, tenant_label, apaleo_account, property_ids,
                                    webhook_secret_hash, created_at, last_activity_at)
            VALUES (:connectionId, :tenantLabel, :apaleoAccount, CAST(:propertyIdsJson AS jsonb),
                    :secretHash, :now, :now)
            ON CONFLICT (id) DO UPDATE SET
                tenant_label = EXCLUDED.tenant_label,
                apaleo_account = EXCLUDED.apaleo_account,
                property_ids = EXCLUDED.property_ids,
                webhook_secret_hash = EXCLUDED.webhook_secret_hash
            """)
  void upsert(
      @Param("connectionId") String connectionId,
      @Param("tenantLabel") String tenantLabel,
      @Param("apaleoAccount") String apaleoAccount,
      @Param("propertyIdsJson") String propertyIdsJson,
      @Param("secretHash") String secretHash,
      @Param("now") Instant now);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update ConnectionEntity c set c.lastActivityAt = :at where c.id = :connectionId"
          + " and c.lastActivityAt < :at")
  int touchActivity(@Param("connectionId") String connectionId, @Param("at") Instant at);
}
