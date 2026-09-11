<!-- conventions · v1.9.0 -->
Shared conventions of the robertblust, guestgraph and companygraph organizations live in
`conventions/`, vendored from robertblust/conventions at the release `conventions.json`
names. Read them before writing or committing anything here.

- `conventions/WRITING.md` — how we write: one voice, three registers, English and German.
- `conventions/WORKING.md` — how we work with git and GitHub.
- `conventions/REPOSITORIES.md` — the family: what each repository is and what pins what.
- `conventions/WRITER.md`, `conventions/TRANSLATOR.md`, `conventions/GLOSSARY.md` — the two roles that
  make a text, and the terms they keep.

Everything below this block is this repository's own. `sh conventions/conventions-sync check`
says whether the copy matches the release, `sync` brings it to the release the pin names, and
`sh conventions/conventions-check` holds this repository's own Markdown to `WRITING.md`. Edit
a shared file in robertblust/conventions, never here.
<!-- end conventions -->
<!-- service-conventions · v0.3.0 -->
The code-level rules of every guestgraph service on the Spring stack live in
`service-conventions/`, vendored from guestgraph/service-conventions at the release
`service-conventions.json` names: the parent build every `pom.xml` takes by path, the source rules,
the architecture rules in `src/test/java/ServiceRulesTest.java`, the diagram script, the workflow
in `.github/workflows/verify.yml`, and this block. `sh service-conventions/service-conventions-sync
check` says whether the copy matches the release, `sync` brings it to the release the pin names,
and `sh service-conventions/service-conventions-check` says what of the list the service lacks.
What every service has, whatever its stack, is `SERVICE.md` there. Edit a shared file in
guestgraph/service-conventions, never here.
<!-- end service-conventions -->

# connector-apaleo — working conventions

The Apaleo connector: reservations and bookings from an Apaleo account into the guest graph, as
a client of the engine's REST API. It is specified in the engine repository, under
`specs/005-apaleo-connector/`, and that specification is the owner of every decision here: this
file says how to build and check, not what to build.

## Build and verify

```bash
./mvnw verify              # tests (Testcontainers, needs Docker), ArchUnit, PMD, Spotless
./mvnw spotless:apply      # fix formatting (google-java-format) — check fails otherwise
sh conventions/conventions-check
```

## Code conventions

The engine's, unchanged: types are referenced by simple name with a proper import, never an
inline fully qualified name, enforced by PMD in `verify`; formatting is google-java-format via
Spotless; comments state constraints the code cannot show. ArchUnit in
`ServiceRulesTest` holds every repository to explicit `@Query` methods, bans the repository
scaffolding that would bypass them, bans ad-hoc EntityManager queries and JdbcClient, and
confines JPA to the `persistence` package, laid out as the engine lays out its own: `entity` and
`repo` beside each other, with no mapper layer because a cache has no domain to map to. Every
repository method takes a `connectionId` parameter or carries a justified `@ConnectionAgnostic`:
one instance serves many connections, so the engine's tenant rule returns with the connection in
the tenant's place.

## Checks

Two jobs, both required by the ruleset on `main`: `verify`, this repository's own, and
`conventions`, called from robertblust/conventions at the pinned tag and shown by GitHub as
`conventions / conventions`. The prose check leaves out `target`, build output.
