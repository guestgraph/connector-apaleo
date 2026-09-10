package io.guestgraph.connector.apaleo.state;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface HeldGuestIdRepo extends Repository<HeldGuestIdEntity, HeldGuestKey> {

  /** Rewritten from every result that carries a guest id (data-model rule 5). */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      nativeQuery = true,
      value =
          """
            INSERT INTO held_guest_id (connection_id, object_type, object_id, role, position,
                                       guest_id, source_record_id, resolution_status, current_guest_ids)
            VALUES (:connectionId, :objectType, :objectId, :role, :position,
                    :guestId, :sourceRecordId, 'ACTIVE', '[]'::jsonb)
            ON CONFLICT (connection_id, object_type, object_id, role, position) DO UPDATE SET
                guest_id = EXCLUDED.guest_id,
                source_record_id = EXCLUDED.source_record_id,
                resolution_status = 'ACTIVE',
                current_guest_ids = '[]'::jsonb
            """)
  void hold(
      @Param("connectionId") String connectionId,
      @Param("objectType") String objectType,
      @Param("objectId") String objectId,
      @Param("role") String role,
      @Param("position") int position,
      @Param("guestId") UUID guestId,
      @Param("sourceRecordId") UUID sourceRecordId);

  @Query(
      "select distinct h.guestId from HeldGuestIdEntity h where h.key.connectionId = :connectionId"
          + " order by h.guestId")
  List<UUID> distinctGuestIds(@Param("connectionId") String connectionId);

  @Query(
      "select h from HeldGuestIdEntity h where h.key.connectionId = :connectionId"
          + " and h.key.objectType = :objectType and h.key.objectId = :objectId"
          + " order by h.key.role, h.key.position")
  List<HeldGuestIdEntity> findByObject(
      @Param("connectionId") String connectionId,
      @Param("objectType") String objectType,
      @Param("objectId") String objectId);

  /** MERGED replaces the id and reads ACTIVE again; SPLIT and RETIRED keep it and wait. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      nativeQuery = true,
      value =
          """
            UPDATE held_guest_id
               SET guest_id = CASE WHEN :status = 'MERGED' THEN CAST(:replacement AS uuid) ELSE guest_id END,
                   resolution_status = CASE WHEN :status = 'MERGED' THEN 'ACTIVE' ELSE :status END,
                   current_guest_ids = CAST(:currentGuestIdsJson AS jsonb),
                   refreshed_at = :at
             WHERE connection_id = :connectionId AND guest_id = :guestId
            """)
  int refresh(
      @Param("connectionId") String connectionId,
      @Param("guestId") UUID guestId,
      @Param("status") String status,
      @Param("replacement") String replacement,
      @Param("currentGuestIdsJson") String currentGuestIdsJson,
      @Param("at") Instant at);

  @Query(
      "select count(h) from HeldGuestIdEntity h where h.key.connectionId = :connectionId"
          + " and h.resolutionStatus in ('SPLIT', 'RETIRED')")
  long countAwaitingPerson(@Param("connectionId") String connectionId);
}
