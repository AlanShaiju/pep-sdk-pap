# PEP SDK — SYNC-only POC

A multi-module Maven project demonstrating the PEP SDK synchronizing JPA entity changes to a
remote PAP **synchronously** (dispatch in `beforeCommit`, PAP can veto the local transaction).
No async/outbox path — this POC is SYNC only. No mock PAP — point it at your real one.

## Modules

```
pep-poc-parent
├── sdk-core               annotations, model, catalog, registry, request builder
├── sdk-processor          compile-time validation + metadata.json generation
├── sdk-client             RestClient + Resilience4j (blocking)
├── sdk-sync               ChangeBuffer, PapTransactionSynchronization,
│                          DivergenceRecorder + STL_PEP_DIVERGENCE entity/repo  ← divergence lives here
├── sdk-spring-boot-starter auto-config + Hibernate listener
└── demo-service           three @PapEntity classes, two endpoints, port 9090
```

The async module and the mock-PAP app from earlier iterations have been removed.

## What it demonstrates

### Three local entities, one PAP entity

`Pipeline`, `Deployment`, and `Monitor` all declare `@PapEntity(entity = "ResourceInstance")`.
They differ only by a `@PapProperty` constant `resource_type_id` (101 / 102 / 103), which is how
the PAP tells them apart. `Tenant` is a plain JPA entity with **no** SDK annotations; each of the
three pulls `tenant_id` from it via `@PapInclude(name = "tenant_id", attribute = "id")`.

### How multiple @PapEntity classes appear in metadata.json

One `entities` array, one entry per class, each keyed by fully-qualified `entityClass`. The shared
`"papEntity": "ResourceInstance"` is just a repeated string — no grouping or dedup. At runtime
`MetadataLoader` keys the registry by `entityClass`, so three classes → three registry entries that
each resolve to the same catalog endpoint independently. `Tenant` does not appear (no `@PapEntity`).

### Ordering — two endpoints

- `POST /resources/ordered` — creates Pipeline, then Deployment, then Monitor, all in one
  `@Transactional` method. All the same operation type (CREATE), so Hibernate preserves call order;
  the SDK dispatches the three PAP calls in exactly that order. Watch the `SYNC dispatch:` log lines.

- `POST /resources/mixed` — in one transaction: updates a Pipeline, creates a Deployment, deletes a
  Monitor. Hibernate's flush regroups actions insert → update → delete regardless of code order, so
  the dispatch order reflects that grouping, not literal call order. (Hit `/ordered` first to seed.)

### Divergence (STL_PEP_DIVERGENCE)

For every successful PAP call in a transaction, the SDK writes a `PENDING` row immediately
(own `REQUIRES_NEW` transaction, durable at once), tagged with a `transaction_id` drawn once per
`beforeCommit`. Then:

- local commit succeeds → `afterCompletion(COMMITTED)` bulk-DELETEs the transaction's PENDING rows.
- local commit fails / PAP rejects a later entity → `afterCompletion(ROLLED_BACK)` bulk-UPDATEs them
  to `REJECTED_DATA` with an `error` note, for an SRE to remove the now-stale PAP records.

Inspect rows: `GET /divergence`.

## Prerequisites

- JDK 21, Maven 3.9+, network access to Maven Central for the first build.
- A local PostgreSQL with a database and user matching `application.yml`:

  ```sql
  CREATE DATABASE pep_demo;
  CREATE USER pep WITH PASSWORD 'pep';
  GRANT ALL PRIVILEGES ON DATABASE pep_demo TO pep;
  ```

  Flyway creates all tables (`V1` demo tables, `V2` divergence table + sequence) on startup.

- A reachable PAP at `pap.sdk.base-url` (default `http://localhost:8080`). Adjust that URL — and,
  if your PAP's contract differs, the catalog at
  `sdk-core/src/main/resources/META-INF/pep-sdk/pap-endpoints.json` — to match.

## Build & run

```bash
mvn clean install
cd demo-service && mvn spring-boot:run     # starts on port 9090
```

## Exercise

```bash
# Ordered: three CREATEs, dispatched Pipeline -> Deployment -> Monitor
curl -s -X POST http://localhost:9090/resources/ordered

# Mixed: update + create + delete in one tx (Hibernate reorders insert->update->delete)
curl -s -X POST http://localhost:9090/resources/mixed

# Inspect any divergence rows
curl -s http://localhost:9090/divergence | jq
```

To see the divergence path produce `REJECTED_DATA` rows, force a local commit failure after a PAP
success — e.g. point at a PAP that returns 200, then add a DB constraint the second entity violates,
so the first entity's PAP row is written PENDING and then flipped to REJECTED_DATA on rollback.

## Verified in this sandbox

Maven Central isn't reachable here, so `mvn install` must run on your machine. What was checked
with plain javac:

- `sdk-core` (annotations, model, exceptions) and the full `sdk-processor` compile cleanly.
- The annotation processor, run for real against all three entities, generates the expected
  `metadata.json` with three independent `ResourceInstance` entries (101/102/103) and emits nothing
  for `Tenant`.

## NOT verified here (no Hibernate/JPA/Jackson/Postgres jars reachable)

Confirm on your machine when you build:

- `PapTransactionSynchronization`, `DivergenceRecorder`, `PapDivergenceEntry`
  (`@JdbcTypeCode(SqlTypes.JSON)`), and `PapDivergenceRepository` compiling against your Hibernate 6.x.
- Postgres accepting the `JSONB` columns and `CREATE SEQUENCE` / `nextval()` in `V2`.
- `@PersistenceContext EntityManager` injected into `@Bean` factory methods under Boot 3.2.3.
- The `REQUIRES_NEW` divergence writes behaving as intended against a real transaction manager
  (each suspends the business transaction briefly — size the connection pool accordingly).
