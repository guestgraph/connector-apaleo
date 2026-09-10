package io.guestgraph.connector.apaleo.state;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface SyncPointRepo extends Repository<SyncPointEntity, SyncPointKey> {

  @Query(
      "select p from SyncPointEntity p where p.key.connectionId = :connectionId"
          + " and p.key.propertyId = :propertyId")
  Optional<SyncPointEntity> find(
      @Param("connectionId") String connectionId, @Param("propertyId") String propertyId);

  @Query(
      "select p from SyncPointEntity p where p.key.connectionId = :connectionId"
          + " order by p.key.propertyId")
  List<SyncPointEntity> findAll(@Param("connectionId") String connectionId);

  /** The point only ever advances (data-model rule 4). */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      nativeQuery = true,
      value =
          """
            INSERT INTO sync_point (connection_id, property_id, modified_through)
            VALUES (:connectionId, :propertyId, :modifiedThrough)
            ON CONFLICT (connection_id, property_id) DO UPDATE SET
                modified_through = GREATEST(sync_point.modified_through, EXCLUDED.modified_through)
            """)
  void advance(
      @Param("connectionId") String connectionId,
      @Param("propertyId") String propertyId,
      @Param("modifiedThrough") Instant modifiedThrough);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update SyncPointEntity p set p.lastFullSyncAt = :at where p.key.connectionId = :connectionId"
          + " and p.key.propertyId = :propertyId")
  int stampFullSync(
      @Param("connectionId") String connectionId,
      @Param("propertyId") String propertyId,
      @Param("at") Instant at);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update SyncPointEntity p set p.lastReconcileAt = :at where p.key.connectionId = :connectionId"
          + " and p.key.propertyId = :propertyId")
  int stampReconcile(
      @Param("connectionId") String connectionId,
      @Param("propertyId") String propertyId,
      @Param("at") Instant at);
}
