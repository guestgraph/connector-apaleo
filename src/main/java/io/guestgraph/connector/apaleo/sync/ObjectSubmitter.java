package io.guestgraph.connector.apaleo.sync;

import io.guestgraph.connector.apaleo.apaleo.model.Booking;
import io.guestgraph.connector.apaleo.apaleo.model.Reservation;
import io.guestgraph.connector.apaleo.config.ConnectionConfig;
import io.guestgraph.connector.apaleo.config.ConnectorProperties;
import io.guestgraph.connector.apaleo.engine.EngineClient;
import io.guestgraph.connector.apaleo.engine.EngineClients;
import io.guestgraph.connector.apaleo.engine.model.IngestRecord;
import io.guestgraph.connector.apaleo.engine.model.IngestResult;
import io.guestgraph.connector.apaleo.mapping.ApaleoMapper;
import io.guestgraph.connector.apaleo.mapping.RosterHash;
import io.guestgraph.connector.apaleo.persistence.ObjectType;
import io.guestgraph.connector.apaleo.persistence.entity.ObjectStateEntity;
import io.guestgraph.connector.apaleo.persistence.repo.HeldGuestIdRepo;
import io.guestgraph.connector.apaleo.persistence.repo.ObjectStateRepo;
import io.guestgraph.connector.apaleo.persistence.repo.SyncRunRepo;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One fetched object version through data-model rules 2, 4 and 5: hash it, submit it when the hash
 * moved, read every result, hold the guest ids, and replace the state — unless the engine refused a
 * record, in which case the state stays for the next attempt (research R7).
 */
@Service
public class ObjectSubmitter {

  private final ObjectStateRepo objectStates;
  private final HeldGuestIdRepo heldGuestIds;
  private final SyncRunRepo syncRuns;
  private final EngineClients engines;
  private final ApaleoMapper mapper;
  private final Clock clock;

  public ObjectSubmitter(
      ObjectStateRepo objectStates,
      HeldGuestIdRepo heldGuestIds,
      SyncRunRepo syncRuns,
      EngineClients engines,
      ConnectorProperties properties,
      Clock clock) {
    this.objectStates = objectStates;
    this.heldGuestIds = heldGuestIds;
    this.syncRuns = syncRuns;
    this.engines = engines;
    this.mapper = new ApaleoMapper(properties.engineSourceSystem());
    this.clock = clock;
  }

  @Transactional
  public Outcome submit(ConnectionConfig c, UUID runId, Reservation reservation) {
    Outcome outcome =
        submit(
            c,
            ObjectType.RESERVATION,
            reservation.id(),
            reservation.propertyId(),
            reservation.bookingId(),
            reservation.modified(),
            reservation.status(),
            RosterHash.of(reservation),
            () -> mapper.map(reservation));
    count(c, runId, 1, outcome);
    return outcome;
  }

  @Transactional
  public Outcome submit(ConnectionConfig c, UUID runId, Booking booking) {
    Outcome outcome =
        submit(
            c,
            ObjectType.BOOKING,
            booking.id(),
            null,
            null,
            booking.modified(),
            null,
            RosterHash.of(booking),
            () -> mapper.map(booking));
    count(c, runId, 0, outcome);
    return outcome;
  }

  /**
   * Whether a version of this object has been submitted; the full sync fetches bookings it lacks.
   */
  @Transactional(readOnly = true)
  public boolean known(ConnectionConfig c, ObjectType type, String objectId) {
    return objectStates.find(c.name(), type.code(), objectId).isPresent();
  }

  private Outcome submit(
      ConnectionConfig c,
      ObjectType type,
      String objectId,
      String propertyId,
      String bookingId,
      String modified,
      String status,
      String hash,
      Supplier<List<IngestRecord>> records) {
    Optional<ObjectStateEntity> state = objectStates.find(c.name(), type.code(), objectId);
    if (state.isPresent() && state.get().getRosterHash().equals(hash)) {
      return Outcome.unchanged();
    }
    // A version whose clock cannot be read is still sent — the engine flags it (spec US1
    // scenario 8) — but nothing valid can be stored for it, so the state stays and it counts.
    Optional<Instant> version = parse(modified);
    List<IngestRecord> batch = records.get();
    if (batch.isEmpty()) {
      // Nothing to send, but the version is known: a booking without a booker is not refetched.
      version.ifPresent(
          at ->
              objectStates.upsert(
                  c.name(),
                  type.code(),
                  objectId,
                  propertyId,
                  bookingId,
                  at,
                  hash,
                  clock.instant(),
                  status));
      return Outcome.unchanged();
    }
    EngineClient engine = engines.forConnection(c);
    Map<String, IngestRecord> byKey = new HashMap<>();
    batch.forEach(r -> byKey.put(r.externalKey(), r));
    List<IngestResult> results = engine.submit(batch);

    int duplicates = 0;
    int flagged = 0;
    // A result the engine did not answer is an error too: its outcome is unknown.
    int errors = Math.max(0, batch.size() - results.size());
    for (IngestResult result : results) {
      IngestRecord record = byKey.get(result.externalKey());
      if (result.failed() || record == null) {
        errors++;
        continue;
      }
      if (result.duplicate()) {
        duplicates++;
      }
      if (result.needsReview()) {
        flagged++;
      }
      if (result.guestId() != null) {
        heldGuestIds.hold(
            c.name(),
            type.code(),
            objectId,
            record.sourceObject().role(),
            record.sourceObject().position() == null ? 0 : record.sourceObject().position(),
            result.guestId(),
            result.sourceRecordId());
      }
    }
    if (version.isEmpty()) {
      errors++;
    }
    if (errors > 0) {
      return new Outcome(Outcome.Kind.FAILED, batch.size(), duplicates, flagged, errors);
    }
    objectStates.upsert(
        c.name(),
        type.code(),
        objectId,
        propertyId,
        bookingId,
        version.get(),
        hash,
        clock.instant(),
        status);
    return new Outcome(Outcome.Kind.SUBMITTED, batch.size(), duplicates, flagged, 0);
  }

  private void count(ConnectionConfig c, UUID runId, int reservationsSeen, Outcome outcome) {
    if (runId == null) {
      return;
    }
    syncRuns.count(
        c.name(),
        runId,
        reservationsSeen,
        outcome.kind() == Outcome.Kind.SUBMITTED ? 1 : 0,
        outcome.records(),
        outcome.duplicates(),
        outcome.flagged(),
        outcome.errors());
  }

  /** Empty when Apaleo's instant is absent or not an instant; never guessed at. */
  static Optional<Instant> parse(String dateTime) {
    if (dateTime == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(OffsetDateTime.parse(dateTime).toInstant());
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
