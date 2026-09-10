package io.guestgraph.connector.apaleo.state;

import jakarta.persistence.Embeddable;

/** One Apaleo delivery under the connection its secret routed it to. */
@Embeddable
public record EventKey(String connectionId, String eventId) {}
