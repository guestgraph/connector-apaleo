package io.guestgraph.connector.apaleo.persistence.repo;

import io.guestgraph.connector.apaleo.persistence.entity.SyncRunEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface SyncRunRepo extends Repository<SyncRunEntity, UUID> {

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      nativeQuery = true,
      value =
          """
            INSERT INTO sync_run (id, connection_id, kind, started_at)
            VALUES (:id, :connectionId, :kind, :startedAt)
            """)
  void start(
      @Param("connectionId") String connectionId,
      @Param("id") UUID id,
      @Param("kind") String kind,
      @Param("startedAt") Instant startedAt);

  @Query("select r from SyncRunEntity r where r.connectionId = :connectionId and r.id = :id")
  Optional<SyncRunEntity> find(@Param("connectionId") String connectionId, @Param("id") UUID id);

  @Query(
      "select r from SyncRunEntity r where r.connectionId = :connectionId"
          + " and r.finishedAt is null order by r.startedAt")
  List<SyncRunEntity> findRunning(@Param("connectionId") String connectionId);

  @Query(
      "select r from SyncRunEntity r where r.connectionId = :connectionId and r.kind = :kind"
          + " and r.outcome = 'SUCCEEDED' order by r.finishedAt desc limit 1")
  Optional<SyncRunEntity> lastSucceeded(
      @Param("connectionId") String connectionId, @Param("kind") String kind);

  /** The last run of the kind that ended, whatever its outcome: when the walk last happened. */
  @Query(
      "select r from SyncRunEntity r where r.connectionId = :connectionId and r.kind = :kind"
          + " and r.finishedAt is not null order by r.finishedAt desc limit 1")
  Optional<SyncRunEntity> lastFinished(
      @Param("connectionId") String connectionId, @Param("kind") String kind);

  /** Counters accumulate as a run proceeds, so the status can show progress. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update SyncRunEntity r set r.reservationsSeen = r.reservationsSeen + :seen,"
          + " r.versionsSubmitted = r.versionsSubmitted + :versions,"
          + " r.recordsSubmitted = r.recordsSubmitted + :records,"
          + " r.duplicates = r.duplicates + :duplicates,"
          + " r.flaggedForReview = r.flaggedForReview + :flagged,"
          + " r.errors = r.errors + :errors"
          + " where r.connectionId = :connectionId and r.id = :id")
  int count(
      @Param("connectionId") String connectionId,
      @Param("id") UUID id,
      @Param("seen") int seen,
      @Param("versions") int versions,
      @Param("records") int records,
      @Param("duplicates") int duplicates,
      @Param("flagged") int flagged,
      @Param("errors") int errors);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update SyncRunEntity r set r.finishedAt = :at, r.outcome = :outcome, r.lastError = :error"
          + " where r.connectionId = :connectionId and r.id = :id")
  int finish(
      @Param("connectionId") String connectionId,
      @Param("id") UUID id,
      @Param("at") Instant at,
      @Param("outcome") String outcome,
      @Param("error") String error);

  /** A run cannot outlive the process that ran it: whatever is open at start was interrupted. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update SyncRunEntity r set r.finishedAt = :at, r.outcome = 'FAILED', r.lastError = :reason"
          + " where r.connectionId = :connectionId and r.finishedAt is null")
  int finishInterrupted(
      @Param("connectionId") String connectionId,
      @Param("at") Instant at,
      @Param("reason") String reason);
}
