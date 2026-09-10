package io.guestgraph.connector.apaleo.sync;

/** The engine answered, and refused records; the count is the reason, never the records. */
public class RecordsRefusedException extends RuntimeException {

  public RecordsRefusedException(int refused) {
    super(refused + " records refused by the engine");
  }
}
