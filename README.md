# connector-apaleo

The Apaleo connector brings a hotel's reservations and bookings from Apaleo into the guest
graph, as a client of the engine's REST API and nothing more: it reads Apaleo, submits
observations, and holds the guest ids the engine answers. It writes nothing into Apaleo.

Every decision about what it does lives in the engine repository, under
[`specs/005-apaleo-connector/`](https://github.com/guestgraph/engine/tree/main/specs/005-apaleo-connector),
and this repository builds what that specification says. Two things there matter before the
rest: a reservation and a booking are two source objects, because Apaleo keeps the guests on
one and the booker on the other, each with its own clock; and the connector owns one database
schema and connects as one role that sees nothing else, so whether it shares the engine's
database is a deployment choice. One instance serves many connections, each an engine tenant
paired with an Apaleo account, and every row and query carries the connection.

## What it reads

Apaleo keeps the people of a stay on two objects, and the connector reads both. A reservation
carries the primary guest and the additional guests, with their positions. A booking carries the
booker and may span several reservations. Each object has its own modified instant, and the
booker is read from the booking, where it lives, not from the copy a reservation shows on
request, because that copy carries the booking's data under the reservation's clock. Every
person on an object version becomes one observation in the engine, keyed by the object's id, the
person's role and position and the object's modified instant; nothing the connector generates
enters that key, so the same source state always produces the same key.

Not every edit reaches the engine. A reservation's modified instant moves for edits that change
no person, and submitting every version would fill the graph with copies whose people had not
changed. The connector keeps, per object, a hash over the people it
last submitted — their role, position and the fields the engine extracts — and submits a version
only when that hash differs, so an address correction alone submits nothing. The
[data model](https://github.com/guestgraph/engine/blob/main/specs/005-apaleo-connector/data-model.md)
states the hash and the rule, and R3 and R4 of the
[research](https://github.com/guestgraph/engine/blob/main/specs/005-apaleo-connector/research.md)
say why.

## Running it

`./mvnw spring-boot:run` starts the connector on port 8081, `server.port` in
`src/main/resources/application.yaml`, and brings up the PostgreSQL of `compose.yaml` beside it,
which it starts and does not stop. Three values have no default and must be set: `CONNECTOR_PUBLIC_URL`, the URL Apaleo
posts to; `CONNECTOR_OPS_TOKEN`, the bearer token of the operations endpoints; and
`CONNECTOR_CONNECTIONS_FILE`, the path of the connections file. Every other value has a default
in `application.yaml`, and the “Configuration” section of the
[data model](https://github.com/guestgraph/engine/blob/main/specs/005-apaleo-connector/data-model.md#configuration)
lists each property and what it means.

## One schema, one role

The connector creates its tables in one schema, `apaleo_connector` by default, and connects as
a role that owns that schema and nothing else. No migration or query names the schema, so the
same build runs in the engine's database or in one of its own; which one is the deployment's
choice. In a shared database the role cannot read the engine's tables, which holds the connector
to the engine's API. Before the first start:

```sql
create role apaleo_connector login password '…';
create schema apaleo_connector authorization apaleo_connector;
```

Point the connector at the database as that role through `DATABASE_URL`, `DATABASE_USERNAME`
and `DATABASE_PASSWORD`; `DATABASE_SCHEMA` changes the schema name. The rule is “One schema, one
role” in the
[data model](https://github.com/guestgraph/engine/blob/main/specs/005-apaleo-connector/data-model.md#one-schema-one-role).

## The connections file

One instance serves many connections. A connection pairs one engine tenant and its API key
with one Apaleo account, its client credentials and its properties; every row of the
connector's state and every query carries the connection, so nothing under one connection is
visible under another, and a failure on one does not stop another. The file
`CONNECTOR_CONNECTIONS_FILE` names is YAML, a `connections:` map keyed by the connection's
name:

```yaml
connections:
  seaside:
    tenantLabel: Seaside Hotels
    engineBaseUrl: https://engine.example
    engineApiKey: …
    apaleoAccount: SEASIDE
    apaleoClientId: …
    apaleoClientSecret: …
    apaleoPropertyIds: [SEA1, SEA2]
    webhookSecret: …
```

An empty `apaleoPropertyIds` means every property of the account. The secrets live in this
file and nowhere else: the database keeps the hash of the webhook secret, because the hash is
what routes a delivery to its connection, and no other credential. The file is refused at
start when a connection lacks a field, when two connections share a name or when two share a
webhook secret. Adding a connection is a change to this file and a restart, since the file is read once at start.

## How changes arrive

Apaleo posts each event to `{CONNECTOR_PUBLIC_URL}/apaleo/events/{webhookSecret}`. The
connector creates that subscription at start, for the connection's properties and the event
types `APALEO_EVENT_TYPES` lists, and replaces one whose endpoint, events or properties differ. A delivery
is stored and answered before anything is fetched, because Apaleo counts any answer but success
as a failure and retries; a worker then fetches the object from Apaleo, applies the hash rule
and submits. A delivery that cannot be fetched or submitted keeps its reason and its next
attempt time and is retried with backoff; it is never dropped, and the status counts it. A
second delivery of the same event id is acknowledged and ignored.

Two runs cover what the webhooks miss. A reconciliation every `RECONCILE_INTERVAL`, fifteen
minutes by default, lists the reservations modified since each property's sync point less
`RECONCILE_OVERLAP`, submits what changed and confirms the subscription still exists. A full
sync walks every reservation and every booking; it runs on first start, on request and on the
connector's own initiative when the gap since its last activity is longer than
`RESYNC_AFTER_GAP`, Apaleo's retry window of 24 hours by default — the one case in which a
booking event can have been lost, because bookings cannot be listed by modification. R5 and R6
of the
[research](https://github.com/guestgraph/engine/blob/main/specs/005-apaleo-connector/research.md)
hold the decisions.

## Watching and driving it

The operations surface is the contract
[connector-api.yaml](https://github.com/guestgraph/engine/blob/main/specs/005-apaleo-connector/contracts/connector-api.yaml).
Behind `Authorization: Bearer {CONNECTOR_OPS_TOKEN}`:

- `GET /status` answers one document with one entry per connection.
- `POST /connections/{name}/sync/full` and `POST /connections/{name}/sync/reconcile` start a run
  on one connection and answer 202 with the run id, or 409 while a run is in progress.
- `GET /connections/{name}/runs/{runId}` reports the run's progress and outcome.
- `POST /connections/{name}/refresh` is in the contract and arrives with user story 4 of the
  specification; it is not served yet.

`/actuator/health` needs no token.

Each status entry says what the connection serves — tenant label, account, properties — the
subscription, a sync point per property, counters since the connection's state was created,
the events pending retry, the splits awaiting a person, the last activity and the last error.
An operator reads four of these first. `lastError` says where the last failure was, Apaleo,
the engine or the database, and why; it never carries a payload or a credential. A rising
`pendingEvents` means deliveries are being stored but not reaching the engine, since a failed
event is never dropped. `subscription.active` false means Apaleo has stopped posting and only
the reconciliation is carrying changes, which is why the reconciliation checks it on every run.
`lastActivityAt` is the instant the gap is measured from: when it falls further behind than
`RESYNC_AFTER_GAP`, the next thing the connector does is a full sync.

## The guest ids it holds

Every submission answers with the guest each person resolved to, and the connector holds that
id per object, role and position, from the latest submitted version; a reassigned reservation
therefore holds the new person's guest. A guest id can be retired after the connector stored it,
by a merge or a split in the engine, so the held ids are re-read and the integrator rule the
engine defines is applied: a merged guest is replaced by its survivor and both ids are logged; a
split or retired guest is kept, marked with the current ids and counted in the status as
awaiting a person; an active guest is left alone. The connector never chooses among several
current guests. The refresh that applies the rule is user story 4 of the specification and is
not built yet; once it lands it runs nightly, on `REFRESH_CRON`, and on request. R8 of the
[research](https://github.com/guestgraph/engine/blob/main/specs/005-apaleo-connector/research.md)
and FR-018 and FR-019 of the
[specification](https://github.com/guestgraph/engine/blob/main/specs/005-apaleo-connector/spec.md)
state the rule.

## Building and checking

`./mvnw verify` runs the tests on Testcontainers, so it needs Docker, then ArchUnit, PMD and
Spotless; `sh conventions/conventions-check` holds the prose to `conventions/WRITING.md`; and
`AGENTS.md` says how to work here. Logs are plain text, and no credential and no person value
appears in them, in the status or in an error.
