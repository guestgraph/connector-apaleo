package io.guestgraph.connector.apaleo.sync;

/** What submitting one object version came to. */
public record Outcome(Kind kind, int records, int duplicates, int flagged, int errors) {

  public enum Kind {
    /** The roster hash matched the last submitted version: nothing sent. */
    UNCHANGED,
    /** Every person submitted and every result read; the state moved. */
    SUBMITTED,
    /** The engine refused at least one record; the state stayed for a retry. */
    FAILED
  }

  static Outcome unchanged() {
    return new Outcome(Kind.UNCHANGED, 0, 0, 0, 0);
  }
}
