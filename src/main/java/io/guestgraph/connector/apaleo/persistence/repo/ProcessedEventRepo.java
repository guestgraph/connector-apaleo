package io.guestgraph.connector.apaleo.persistence.repo;

import io.guestgraph.connector.apaleo.persistence.entity.EventKey;
import io.guestgraph.connector.apaleo.persistence.entity.ProcessedEventEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepo extends Repository<ProcessedEventEntity, EventKey> {

  /** Stores a delivery once: the second delivery of the same id inserts nothing and answers 0. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      nativeQuery = true,
      value =
          """
            INSERT INTO processed_event (connection_id, event_id, event_type, object_type, object_id,
                                         property_id, received_at, state)
            VALUES (:connectionId, :eventId, :eventType, :objectType, :objectId, :propertyId, :now, :state)
            ON CONFLICT (connection_id, event_id) DO NOTHING
            """)
  int insertIfAbsent(
      @Param("connectionId") String connectionId,
      @Param("eventId") String eventId,
      @Param("eventType") String eventType,
      @Param("objectType") String objectType,
      @Param("objectId") String objectId,
      @Param("propertyId") String propertyId,
      @Param("now") Instant now,
      @Param("state") String state);

  @Query(
      "select e from ProcessedEventEntity e where e.key.connectionId = :connectionId"
          + " and e.key.eventId = :eventId")
  Optional<ProcessedEventEntity> find(
      @Param("connectionId") String connectionId, @Param("eventId") String eventId);

  @Query(
      "select e from ProcessedEventEntity e where e.key.connectionId = :connectionId"
          + " and e.state = 'PENDING' and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)"
          + " order by e.receivedAt, e.key.eventId")
  List<ProcessedEventEntity> findDue(
      @Param("connectionId") String connectionId, @Param("now") Instant now);

  @Query(
      "select count(e) from ProcessedEventEntity e where e.key.connectionId = :connectionId"
          + " and e.state = 'PENDING'")
  long countPending(@Param("connectionId") String connectionId);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update ProcessedEventEntity e set e.state = 'DONE', e.nextAttemptAt = null,"
          + " e.lastError = null where e.key.connectionId = :connectionId"
          + " and e.key.eventId = :eventId")
  int markDone(@Param("connectionId") String connectionId, @Param("eventId") String eventId);

  /** Stays PENDING: a failed event is retried, never dropped (FR-011). */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update ProcessedEventEntity e set e.attempts = e.attempts + 1,"
          + " e.nextAttemptAt = :nextAttemptAt, e.lastError = :reason"
          + " where e.key.connectionId = :connectionId and e.key.eventId = :eventId")
  int markFailed(
      @Param("connectionId") String connectionId,
      @Param("eventId") String eventId,
      @Param("nextAttemptAt") Instant nextAttemptAt,
      @Param("reason") String reason);
}
