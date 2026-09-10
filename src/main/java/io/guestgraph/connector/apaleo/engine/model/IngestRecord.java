package io.guestgraph.connector.apaleo.engine.model;

import java.util.Map;

/** One record of the engine's ingest contract (slice 1 of the engine, with the slice 3 block). */
public record IngestRecord(
    String sourceSystem,
    String externalKey,
    String recordTimestamp,
    Map<String, Object> payload,
    SourceObject sourceObject) {}
