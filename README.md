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
database is a deployment choice.

The connector is being built. `./mvnw verify` runs what exists, `sh conventions/conventions-check`
holds the prose to the family's conventions, and `AGENTS.md` says how to work here.
