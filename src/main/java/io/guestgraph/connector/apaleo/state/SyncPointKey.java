package io.guestgraph.connector.apaleo.state;

import jakarta.persistence.Embeddable;

/** One property under one connection. */
@Embeddable
public record SyncPointKey(String connectionId, String propertyId) {}
