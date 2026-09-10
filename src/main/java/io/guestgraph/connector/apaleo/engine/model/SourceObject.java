package io.guestgraph.connector.apaleo.engine.model;

/**
 * The engine's source-object block (slice 3 of the engine): which object, which role, which
 * version.
 */
public record SourceObject(
    String type,
    String id,
    String role,
    Integer position,
    String version,
    String businessStart,
    String businessEnd) {}
