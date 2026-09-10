package io.guestgraph.connector.apaleo.persistence.repo;

import io.guestgraph.connector.apaleo.persistence.entity.ConnectionEntity;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ConnectionRepo extends Repository<ConnectionEntity, String> {

  @Query("select c from ConnectionEntity c where c.id = :connectionId")
  Optional<ConnectionEntity> find(@Param("connectionId") String connectionId);

  /**
   * Native: jsonb cast, and an upsert keeps configuration re-reads idempotent. The file is the
   * authority (FR-015a): a delivery is routed by {@code Connections}, never by this table, and a
   * connection removed from the file keeps its row and its state, unread until configured again,
   * because the rows that reference it are a cache of history worth keeping; its secret hash is not
   * unique in the table for the same reason.
   */
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

  /** The status counters accumulate over every submission, in a run or from an event. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update ConnectionEntity c set c.versionsSubmitted = c.versionsSubmitted + :versions,"
          + " c.recordsSubmitted = c.recordsSubmitted + :records,"
          + " c.duplicates = c.duplicates + :duplicates,"
          + " c.flaggedForReview = c.flaggedForReview + :flagged,"
          + " c.errors = c.errors + :errors where c.id = :connectionId")
  int count(
      @Param("connectionId") String connectionId,
      @Param("versions") int versions,
      @Param("records") int records,
      @Param("duplicates") int duplicates,
      @Param("flagged") int flagged,
      @Param("errors") int errors);

  /** The row, locked until the transaction ends: one run at a time starts on a connection. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from ConnectionEntity c where c.id = :connectionId")
  Optional<ConnectionEntity> lock(@Param("connectionId") String connectionId);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update ConnectionEntity c set c.lastActivityAt = :at where c.id = :connectionId"
          + " and c.lastActivityAt < :at")
  int touchActivity(@Param("connectionId") String connectionId, @Param("at") Instant at);

  /**
   * Moves the activity only when it is not older than {@code notBefore}: an event that arrives
   * after a gap must not close the gap, since only a full sync recovers what the gap lost.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update ConnectionEntity c set c.lastActivityAt = :at where c.id = :connectionId"
          + " and c.lastActivityAt < :at and c.lastActivityAt >= :notBefore")
  int touchActivityUnlessGap(
      @Param("connectionId") String connectionId,
      @Param("at") Instant at,
      @Param("notBefore") Instant notBefore);
}
