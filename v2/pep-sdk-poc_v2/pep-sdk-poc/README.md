# PEP SDK — POC (design v9)

Two Spring Boot apps demonstrate the SDK end-to-end:

- **demo-service** (port 8080) — uses the SDK to mirror `Pipeline` JPA changes to the PAP.
- **mock-pap** (port 9090) — logs each PAP call and returns 200.

The SDK is six Maven modules: `sdk-core`, `sdk-processor`, `sdk-client`, `sdk-sync`,
`sdk-async`, `sdk-spring-boot-starter`. The full design is in `pep-sdk-design.md` (v9).

## What v9 showcases

The demo `Pipeline` entity uses all three source-annotation types, resolved with the
precedence **@PapProperty > @PapAttribute > @PapInclude**:

| Source | Annotation | Where the value comes from |
|---|---|---|
| `propagate`, `resource_type_id` | `@PapProperty` | class-level constants (no entity column) |
| `id`, `code`, `name`, `description` | `@PapAttribute` | entity fields |
| `tenant_id` | `@PapInclude(name="tenant_id", attribute="id")` | the related `Tenant`'s `id` — `pipeline.tenant.id` |

`Tenant` itself carries **no SDK annotations** — the parent declares what to pull and
what to call it. Dot-paths (`attribute = "region.code"`) are supported for deeper walks.

## Prerequisites

JDK 21, Maven 3.9+, network access to Maven Central for the first build.

## Build

```bash
mvn clean install
```

The annotation processor runs on demo-service and validates `Pipeline` against the bundled
catalog: every source name the catalog references must be covered by `@PapAttribute`,
`@PapProperty`, or `@PapInclude`. Intra-type duplicates are compile errors; cross-type
duplicates compile with a precedence warning. Invalid `@PapInclude.attribute` dot-paths
are compile errors.

## Run

```bash
# terminal 1
cd mock-pap && mvn spring-boot:run

# terminal 2
cd demo-service && mvn spring-boot:run
```

Startup seeds one Tenant (id=7, code=ACME).

## Exercising the flows

### SYNC CREATE — all three source types in one request

```bash
curl -s -X POST http://localhost:8080/pipelines \
  -H 'Content-Type: application/json' \
  -d '{"code":"P-001","name":"first","description":"hello","tenantId":7}'
```

mock-pap logs:

```
[MOCK-PAP] CREATE tenant_id(header)=7 body={resource_instance_code=P-001, resource_instance_name=first, description=hello, resource_type_id=102}
```

Trace each value: `tenant_id` header came from **@PapInclude** (pipeline.tenant.id);
`resource_type_id` in the body came from **@PapProperty**; the rest from **@PapAttribute**.

### SYNC UPDATE — query param from @PapProperty

```bash
curl -s -X PATCH http://localhost:8080/pipelines/1 \
  -H 'Content-Type: application/json' \
  -d '{"name":"renamed"}'
```

```
[MOCK-PAP] UPDATE id=1 tenant_id(header)=7 propagate(query)=true body={resource_instance_name=renamed, description=hello}
```

### ASYNC DELETE — via the outbox

```bash
curl -s -X DELETE http://localhost:8080/pipelines/1
```

`Pipeline` declares DELETE as ASYNC: the change lands in `STL_PEP_OUTBOX`, the consumer
claims it within the 2s poll interval, looks up the path template from the catalog, and
dispatches:

```
[demo-service] ASYNC enqueue: DELETE ResourceInstance (entity=1)
[demo-service] ASYNC dispatch: DELETE /pap/v1/resource-instances/1 (entity=ResourceInstance, row=1)
[mock-pap]     [MOCK-PAP] DELETE id=1 tenant_id(header)=7 propagate(query)=true
```

### Compile-time validation demos

Each of these breaks the build with a targeted error:

1. `@PapEntity(entity = "Nope", ...)` → *entity "Nope" is not in the PAP catalog*.
2. Remove `@PapInclude(name = "tenant_id", ...)` → *attribute source 'tenant_id' is referenced
   by the catalog ... but has no mapping*.
3. `@PapInclude(name = "tenant_id", attribute = "wrong")` → *@PapInclude.attribute 'wrong'
   does not resolve: type Tenant has no field 'wrong'*.
4. Add a second `@PapAttribute(attributeName = "code")` field → *duplicate @PapAttribute*.
5. Add `@PapProperty(key = "code", value = "X")` → builds, but warns: *source 'code' is
   supplied by both @PapProperty and @PapAttribute; @PapProperty wins by precedence*.

## Verified in this environment

Maven Central isn't reachable from the build sandbox, so `mvn install` must run on your
machine. What was verified here with plain javac:

- sdk-core (annotations, model, exceptions) and the full sdk-processor compile cleanly.
- The compile-time catalog parser extracts all 7 source references from the v9 catalog.
- A functional test of `PapEntityDescriptor.resolveSources` passes: precedence
  (@PapProperty over colliding @PapInclude), single-hop and nested dot-path includes,
  and null-relationship safety.
- The `DivergenceRecorder` SPI interface (no external dependencies) compiles standalone.

**Not verified in this environment** — no Hibernate, JPA, Jackson, or H2 jars are reachable
from this sandbox, so none of the following has been compile- or run-checked against real
artifacts:

- `PapTransactionSynchronization`, `PapDivergenceEntry` (`@JdbcTypeCode(SqlTypes.JSON)`),
  `PapDivergenceRepository`, `DefaultDivergenceRecorder` — confirm these compile against
  your actual Hibernate 6.x version when you build.
- Whether H2 (even in PostgreSQL-compatibility mode) accepts the `JSONB` column type and the
  `CREATE SEQUENCE` / `nextval()` syntax used in `V2__pep_divergence.sql`. If H2 rejects
  `JSONB`, the simplest fix for the demo is swapping it for `TEXT` in that one migration file
  while keeping `JSONB` as the documented production type (the design doc's schema is
  unaffected either way).
- `@PersistenceContext EntityManager` as a `@Bean` factory-method parameter in
  `PapSdkAutoConfiguration` — this is standard, documented Spring behavior, but unconfirmed
  against the actual Spring Boot 3.2.3 dependency versions you'll build with.

## Known POC limitations

- Datatype validation: none (by design — see design doc §14.4).
- Outbox consumer is single-threaded; no per-key partitioning.
- H2 in-memory DB resets on restart.
- Mock PAP always returns 200; no scripted failure scenarios — so the divergence-table path
  (PENDING → REJECTED_DATA) can't be exercised end-to-end without manually forcing a local
  commit failure (e.g., a unique constraint violation on the second of two entities) or
  pointing the demo at a mock PAP that accepts the call then having the local save fail.
- `@PapInclude` cycle depth limit is enforced at runtime walk but the processor's cycle
  warning is not implemented in this POC.
- No automated test exercises the divergence table's bulk resolve queries
  (`deletePendingByTransactionId` / `markRejectedByTransactionId`) against a real database.

## Layout

```
pep-sdk-poc/
├── pom.xml                          parent
├── sdk-core/                        annotations (incl. @PapInclude), model, catalog, registry, request
├── sdk-processor/                   compile-time validation (DotPathValidator) + metadata.json
├── sdk-client/                      RestClient + Resilience4j
├── sdk-sync/                        ChangeBuffer + TxnSynchronization
├── sdk-async/                       outbox entity + consumer + V1 migration
├── sdk-spring-boot-starter/         auto-config + Hibernate listener
├── mock-pap/                        Spring Boot — port 9090
└── demo-service/                    Spring Boot — port 8080 (Pipeline + Tenant)
```
