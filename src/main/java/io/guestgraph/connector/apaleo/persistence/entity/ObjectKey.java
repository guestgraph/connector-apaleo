package io.guestgraph.connector.apaleo.persistence.entity;

import io.guestgraph.connector.apaleo.persistence.ObjectType;
import jakarta.persistence.Embeddable;

/** One Apaleo object under one connection; the primary key of {@code object_state}. */
@Embeddable
public record ObjectKey(String connectionId, String objectType, String objectId) {

  public static ObjectKey of(String connectionId, ObjectType type, String objectId) {
    return new ObjectKey(connectionId, type.code(), objectId);
  }
}
