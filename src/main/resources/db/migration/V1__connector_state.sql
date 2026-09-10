-- The connector's own state, all of it a cache of Apaleo's and the engine's facts
-- (specs/005-apaleo-connector/data-model.md in the engine repository). No schema is named:
-- Flyway's default schema and the connection's search path place these in DATABASE_SCHEMA.
-- One instance serves many connections, so every table starts with connection_id. The
-- configuration file, not this table, routes a delivery and refuses two live connections
-- sharing a secret; a connection removed from the file keeps its row as history, so the
-- secret hash carries no uniqueness here on purpose.

CREATE TABLE connection (
    id                  text PRIMARY KEY,
    tenant_label        text NOT NULL,
    apaleo_account      text NOT NULL,
    property_ids        jsonb NOT NULL DEFAULT '[]'::jsonb,
    webhook_secret_hash text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    last_activity_at    timestamptz NOT NULL DEFAULT now()
);


CREATE TABLE object_state (
    connection_id     text NOT NULL REFERENCES connection (id),
    object_type       text NOT NULL CHECK (object_type IN ('reservation', 'booking')),
    object_id         text NOT NULL,
    property_id       text,
    booking_id        text,
    last_modified     timestamptz NOT NULL,
    roster_hash       text NOT NULL,
    last_submitted_at timestamptz NOT NULL,
    last_status       text,
    PRIMARY KEY (connection_id, object_type, object_id)
);

CREATE TABLE processed_event (
    connection_id   text NOT NULL REFERENCES connection (id),
    event_id        text NOT NULL,
    event_type      text NOT NULL,
    object_type     text NOT NULL,
    object_id       text NOT NULL,
    property_id     text NOT NULL,
    received_at     timestamptz NOT NULL DEFAULT now(),
    state           text NOT NULL CHECK (state IN ('PENDING', 'DONE', 'IGNORED', 'FAILED')),
    attempts        int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_error      text,
    PRIMARY KEY (connection_id, event_id)
);

CREATE INDEX processed_event_pending_idx
    ON processed_event (connection_id, received_at) WHERE state = 'PENDING';

CREATE TABLE sync_point (
    connection_id     text NOT NULL REFERENCES connection (id),
    property_id       text NOT NULL,
    modified_through  timestamptz NOT NULL,
    last_full_sync_at timestamptz,
    last_reconcile_at timestamptz,
    PRIMARY KEY (connection_id, property_id)
);

CREATE TABLE sync_run (
    id                 uuid PRIMARY KEY,
    connection_id      text NOT NULL REFERENCES connection (id),
    kind               text NOT NULL CHECK (kind IN ('FULL', 'RECONCILE', 'REFRESH')),
    started_at         timestamptz NOT NULL DEFAULT now(),
    finished_at        timestamptz,
    outcome            text CHECK (outcome IN ('SUCCEEDED', 'FAILED')),
    reservations_seen  int NOT NULL DEFAULT 0,
    versions_submitted int NOT NULL DEFAULT 0,
    records_submitted  int NOT NULL DEFAULT 0,
    duplicates         int NOT NULL DEFAULT 0,
    flagged_for_review int NOT NULL DEFAULT 0,
    errors             int NOT NULL DEFAULT 0,
    last_error         text
);

CREATE INDEX sync_run_connection_idx ON sync_run (connection_id, started_at);

CREATE TABLE held_guest_id (
    connection_id     text NOT NULL REFERENCES connection (id),
    object_type       text NOT NULL CHECK (object_type IN ('reservation', 'booking')),
    object_id         text NOT NULL,
    role              text NOT NULL CHECK (role IN ('PRIMARY_GUEST', 'ADDITIONAL_GUEST', 'BOOKER')),
    position          int NOT NULL DEFAULT 0,
    guest_id          uuid NOT NULL,
    source_record_id  uuid NOT NULL,
    resolution_status text NOT NULL CHECK (resolution_status IN ('ACTIVE', 'MERGED', 'SPLIT', 'RETIRED')),
    current_guest_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    refreshed_at      timestamptz,
    PRIMARY KEY (connection_id, object_type, object_id, role, position)
);

CREATE INDEX held_guest_id_guest_idx ON held_guest_id (connection_id, guest_id);
