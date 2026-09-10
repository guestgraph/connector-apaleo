package io.guestgraph.connector.apaleo.persistence;

/** The two Apaleo objects the connector observes, each with its own clock and roster. */
public enum ObjectType {
  RESERVATION("reservation"),
  BOOKING("booking");

  private final String code;

  ObjectType(String code) {
    this.code = code;
  }

  /** The value stored and sent to the engine as the source-object type. */
  public String code() {
    return code;
  }

  public static ObjectType fromCode(String code) {
    for (ObjectType type : values()) {
      if (type.code.equals(code)) {
        return type;
      }
    }
    throw new IllegalArgumentException("Unknown object type " + code);
  }
}
