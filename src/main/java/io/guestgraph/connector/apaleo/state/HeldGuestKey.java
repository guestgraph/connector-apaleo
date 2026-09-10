package io.guestgraph.connector.apaleo.state;

import jakarta.persistence.Embeddable;

/** One person slot on one object under one connection. */
@Embeddable
public record HeldGuestKey(
    String connectionId, String objectType, String objectId, String role, int position) {}
