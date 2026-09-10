package io.guestgraph.connector.apaleo.persistence.repo;

import io.guestgraph.connector.apaleo.persistence.entity.ObjectKey;
import io.guestgraph.connector.apaleo.persistence.entity.ObjectStateEntity;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ObjectStateRepo extends Repository<ObjectStateEntity, ObjectKey> {

  @Query(
      "select s from ObjectStateEntity s where s.key.connectionId = :connectionId"
          + " and s.key.objectType = :objectType and s.key.objectId = :objectId")
  Optional<ObjectStateEntity> find(
      @Param("connectionId") String connectionId,
      @Param("objectType") String objectType,
      @Param("objectId") String objectId);

  /**
   * Replace the row after a submission. {@code last_modified} never moves backwards: an older
   * version submitted late still counts, but the engine orders versions, not the connector.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      nativeQuery = true,
      value =
          """
            INSERT INTO object_state (connection_id, object_type, object_id, property_id, booking_id,
                                      last_modified, roster_hash, last_submitted_at, last_status)
            VALUES (:connectionId, :objectType, :objectId, :propertyId, :bookingId,
                    :lastModified, :rosterHash, :now, :lastStatus)
            ON CONFLICT (connection_id, object_type, object_id) DO UPDATE SET
                property_id = EXCLUDED.property_id,
                booking_id = EXCLUDED.booking_id,
                last_modified = GREATEST(object_state.last_modified, EXCLUDED.last_modified),
                roster_hash = EXCLUDED.roster_hash,
                last_submitted_at = EXCLUDED.last_submitted_at,
                last_status = EXCLUDED.last_status
            """)
  void upsert(
      @Param("connectionId") String connectionId,
      @Param("objectType") String objectType,
      @Param("objectId") String objectId,
      @Param("propertyId") String propertyId,
      @Param("bookingId") String bookingId,
      @Param("lastModified") Instant lastModified,
      @Param("rosterHash") String rosterHash,
      @Param("now") Instant now,
      @Param("lastStatus") String lastStatus);
}
