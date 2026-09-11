package io.guestgraph.connector.apaleo.api.ops;

import io.guestgraph.connector.apaleo.apaleo.ApaleoException;
import io.guestgraph.connector.apaleo.engine.EngineException;
import io.guestgraph.connector.apaleo.sync.RecordsRefusedException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * The last failure per connection, for the status (research R9): where it happened, when, and the
 * reason as the exception states it, which is a status and a path, never a payload and never a
 * credential. Kept in memory: a restart starts with none, and the log has the history.
 */
@Component
public class LastErrors {

  public enum Where {
    APALEO,
    ENGINE,
    DATABASE
  }

  public record LastError(Instant at, Where where, String reason) {}

  private final Map<String, LastError> errors = new ConcurrentHashMap<>();
  private final Clock clock;

  public LastErrors(Clock clock) {
    this.clock = clock;
  }

  public void record(String connectionId, Where where, String reason) {
    errors.put(connectionId, new LastError(clock.instant(), where, reason));
  }

  /** Where the exception says; {@code fallback} for one that names no system. */
  public void record(String connectionId, Throwable failure, Where fallback) {
    record(connectionId, whereOf(failure, fallback), failure.getMessage());
  }

  /** Forgets the connection's error, as an acknowledgment would. */
  public void clear(String connectionId) {
    errors.remove(connectionId);
  }

  public Optional<LastError> find(String connectionId) {
    return Optional.ofNullable(errors.get(connectionId));
  }

  static Where whereOf(Throwable failure, Where fallback) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof ApaleoException) {
        return Where.APALEO;
      }
      if (t instanceof EngineException || t instanceof RecordsRefusedException) {
        return Where.ENGINE;
      }
      if (t instanceof DataAccessException || t instanceof TransactionException) {
        return Where.DATABASE;
      }
    }
    return fallback;
  }
}
