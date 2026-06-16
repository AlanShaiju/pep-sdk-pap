# PEP SDK — Design Document (v13)

## 1. Introduction

The PEP SDK is a Java/Spring Boot library that synchronizes JPA-managed entity changes to a remote Policy Administration Point (PAP). The developer's only contract with the SDK is annotations on JPA entities. Service code is untouched.

### 1.1 Two-phase operation

- **Compile time.** An annotation processor inspects `@PapEntity` declarations, validates against the bundled PAP catalog, and emits one `metadata.json` per Maven module.
- **Runtime.** At startup, the SDK loads metadata into a registry and registers a Hibernate event listener. Every persist/update/delete on a `@PapEntity` flows through the SDK to the PAP.

### 1.2 What changed in v13

- **`STL_PEP_DIVERGENCE` schema adopted as specified.** Replaces the earlier 3-status (`NEEDS_RECONCILIATION`/`COMPENSATION_ATTEMPTED`/`RESOLVED`) design with the exact 2-status (`PENDING`/`REJECTED_DATA`) schema, `transaction_id` (`INTEGER`, sequence-generated) in place of the earlier `transaction_group_id` (`UUID`), and a single `payload`/`error` `JSONB` pair in place of the earlier multi-column layout. See §12.1.
- **Rows are now written PENDING immediately at dispatch, not reactively on rollback.** Closes most of the residual crash window flagged in v12's open issues, at the cost of one extra INSERT (and, on the happy path, one extra bulk DELETE) per successful SYNC dispatch.
- **Auto-compensation removed.** The optional auto-compensation tier from v10–v12 doesn't fit a 2-status model (it needs a third status to track attempt state) and is dropped. `REJECTED_DATA` rows are for manual reconciliation only.
- **New §15 — Lifecycle: compile time → startup → runtime.** Consolidates, in one place, what happens at each phase: annotation-processor validation, `MetadataLoader`/registry construction, Hibernate listener registration, and the request-driven runtime flow including exactly which Hibernate events `PapEntityListener` observes and where `TransactionSynchronization` participates.

### 1.3 What changed in v12

- **Explicit flush actually implemented, and its necessity corrected.** v11 described the explicit `entityManager.flush()` in `beforeCommit` as defensive hardening; it is in fact required given Spring's documented transaction-manager phase ordering (`triggerBeforeCommit()` runs before `doCommit()`, and `doCommit()` is what triggers Hibernate's own commit-time flush). Without it, deferred UPDATE/DELETE (and non-`IDENTITY` CREATE) would silently never reach the SDK's buffer. v11's text described the fix; v12 implements it — `PapTransactionSynchronization` now takes an `EntityManager` and flushes before draining the buffer.
- **New open issue: transaction granularity (§14.14).** Surfaces the fork between the current one-local-transaction-per-method design (a PAP rejection partway through a multi-entity dispatch rolls back everything locally, even already-PAP-confirmed entities — handled via divergence rows) versus an alternative per-entity transaction scope that eliminates that divergence window at the cost of giving up cross-entity local atomicity. Not implemented; flagged as a decision, not a default.

### 1.4 What changed in v11

- **Multi-entity dispatch ordering made explicit (§8.2).** Documents exactly what the SDK guarantees about call order when one transaction touches several `@PapEntity` instances, and where that guarantee breaks (`hibernate.order_inserts`/`order_updates`, mixed operation types, PAP-side consistency).
- **Multi-target dispatch documented (§8.3).** Several local entity classes mapping to the same PAP entity, and a transaction mixing PAP entity targets, were already supported by the existing per-change descriptor lookup — this section makes that explicit rather than leaving it implicit.
- **Startup validation added.** `HibernateListenerRegistrar` now warns if `hibernate.order_inserts` or `hibernate.order_updates` is enabled, since either silently breaks the dispatch-order guarantee.
- **Explicit flush in `beforeCommit` — described, not yet implemented.** v11's text named the fix (flush before draining the buffer) but the code change was not actually made; v12 corrects this (see §1.2).

### 1.5 What changed in v10

- **SYNC failure handling — compensating outbox, refined to a divergence table.** The SYNC path (`beforeCommit` dispatch) has a defined mechanism for the dual-write window where the PAP write succeeds but the local DB commit then fails. See §12.1.
- **Implementation focus is SYNC.** Both `CommunicationMode` values remain in the design; build effort prioritizes SYNC and its failure-handling mechanism.

### 1.6 What changed in v9

- **Explicit precedence between annotation types.** `@PapProperty` > `@PapAttribute` > `@PapInclude`. Cross-type same-name collisions are silent and deterministic, not compile-time errors.
- **Intra-type collisions stay errors.** Two `@PapAttribute` with same name, two `@PapInclude.name` equal, or two `@PapProperty` keys equal are still compile-time errors — precedence can't disambiguate same-type declarations.
- **Design philosophy reversal noted.** `@PapProperty` is now authoritative, not a default. If declared, it wins over entity-derived values for that source name.

## 2. Catalog format — `pap-endpoints.json`

### 2.1 Structure

```json
{
  "ResourceInstance": {
    "create": {
      "path": "/pap/v1/resource-instances",
      "pathVariables": {},
      "headers":       { "tenant_id": "tenant_id" },
      "queryParams":   {},
      "body":          { "resource_instance_code": "code",
                         "resource_instance_name": "name",
                         "description":            "description",
                         "resource_type_id":       "resource_type_id" }
    },
    "update": {
      "path": "/pap/v1/resource-instances/{id}",
      "pathVariables": { "id": "id" },
      "headers":       { "tenant_id": "tenant_id" },
      "queryParams":   { "propagate": "propagate" },
      "body":          { "resource_instance_name": "name",
                         "description":            "description" }
    },
    "delete": {
      "path": "/pap/v1/resource-instances/{id}",
      "pathVariables": { "id": "id" },
      "headers":       { "tenant_id": "tenant_id" },
      "queryParams":   { "propagate": "propagate" },
      "body":          {}
    }
  }
}
```

### 2.2 Field semantics

| Field | Type | Description |
|---|---|---|
| `path` | string | URL template; `{name}` placeholders bind to `pathVariables` keys. |
| `pathVariables` | map | Path placeholder name → source name. |
| `headers` | map | HTTP header name → source name. |
| `queryParams` | map | Query parameter key → source name. |
| `body` | map | Body field name → source name. SDK serializes as JSON. DELETE typically empty. |

**Catalog map value = source name.** The SDK looks it up in the merged source map (§4).

### 2.3 Where the catalog lives

- `sdk-core/src/main/resources/META-INF/pep-sdk/pap-endpoints.json` — runtime
- `sdk-processor/src/main/resources/META-INF/pep-sdk/pap-endpoints.json` — compile-time copy

The SDK build keeps the two in sync.

## 3. Annotations

### 3.1 `@PapEntity` (class-level)

Marks a JPA entity as mirroring a PAP entity.

| Property | Type | Required | Description |
|---|---|---|---|
| `entity` | `String` | Yes | PAP entity type. Must exist in the catalog. |
| `properties` | `PapProperty[]` | No | Static (per-class) authoritative source values. |
| `operationModes` | `PapOperationMode[]` | No | Per-operation communication mode. |

### 3.2 `@PapAttribute` (field-level)

Names the source this field provides.

| Property | Type | Required | Description |
|---|---|---|---|
| `attributeName` | `String` | Yes | Source name. |

Applied to a field on a `@PapEntity` class. The field's value enters the source map under `attributeName` *unless* a higher-precedence declaration (`@PapProperty`) supplies the same name.

**Intra-type uniqueness rule.** Within a single class, two `@PapAttribute` fields cannot declare the same `attributeName`. Compile-time error — precedence can't disambiguate which field to read.

A field can carry `@PapAttribute` even if no catalog operation currently references its source name.

### 3.3 `@PapProperty` (nested)

Inside `@PapEntity.properties[]`. One key-value pair, static per class. **Authoritative** — wins over any same-named `@PapAttribute` or `@PapInclude` on the same class.

```java
properties = {
    @PapProperty(key = "propagate",        value = "true"),
    @PapProperty(key = "resource_type_id", value = "102")
}
```

Use it for:
- Catalog source names that don't correspond to any entity field or relationship (body fields with no column).
- Values that are class-level constants and must not vary per instance.

Do **not** use `@PapProperty` for values you expect to be overridden by entity state — there's no "fallback" semantics anymore. If declared, the property always wins.

**Intra-type uniqueness rule.** No two entries with the same `key`. Compile-time error.

### 3.4 `@PapInclude` (field-level)

Marks a `@ManyToOne` or `@OneToOne` relationship field. Each `@PapInclude` declares one source-map entry pulled from the related entity. Multiple `@PapInclude` annotations may stack on one relationship field (`@Repeatable`).

| Property | Type | Required | Description |
|---|---|---|---|
| `name` | `String` | Yes | Source name in the owning entity's source map. |
| `attribute` | `String` | Yes | Java field name on the related entity. Dot-paths supported for nested walks (`"region.code"`). |

```java
@ManyToOne(fetch = FetchType.EAGER)
@PapInclude(name = "tenant_id",   attribute = "id")
@PapInclude(name = "tenant_code", attribute = "code")
@PapInclude(name = "tenant_name", attribute = "name")
private Tenant tenant;
```

The included class (`Tenant`) doesn't need any SDK annotations to be includable. The parent declares what to pull (`attribute`) and what to call it (`name`).

#### Constraints

- **Single-valued relationships only.** Collections cause a processor error.
- **Field must exist.** Each segment of the `attribute` dot-path must resolve to a Java field on the corresponding type. Otherwise:
  - **Compile time** — the processor errors with `@PapInclude.attribute 'region.code' does not resolve: type Tenant has no field 'region'`. This is the primary line of defence.
  - **Runtime** — if the resolution somehow fails at startup (classpath drift between modules, missing class), `MetadataLoader` throws `PapSdkException` and the app fails to start. The `@PapInclude` is never reached with an invalid path during a request.
- **Fetch type matters.** `LAZY` may trigger an unexpected SQL query inside the Hibernate listener. Warning at startup.
- **Cycles** bounded by `pap.sdk.include.max-depth` (default 5).
- **Null value vs. missing field.** Distinct cases with different handling:
  - *Missing field* (path doesn't exist on the type): compile-time error, can't reach runtime.
  - *Null value* (path exists, traversal hits null mid-walk): the include contributes nothing, source-map entry is absent. Catalog references to absent sources fail at request build with `PapSdkException("No value for source 'X'")` for path/header/query, or are silently omitted from the body.

#### Intra-type uniqueness rule

Two `@PapInclude` annotations on the same class — whether stacked on one relationship or split across different fields — cannot share a `name`. Compile-time error.

### 3.5 `@PapIncludes` (container)

Standard Java repeating-annotation container. Compiler-synthesized; developers don't reference it directly.

### 3.6 `@PapOperationMode` (nested)

Inside `@PapEntity.operationModes[]`. Pairs an `Operation` with a `CommunicationMode`. No duplicate `operation` values.

### 3.7 `CommunicationMode` (enum)

`SYNC` | `ASYNC`.

## 4. Source resolution

When `PapRequestBuilder` constructs a request, it resolves each catalog source name by checking sources in **strict precedence order**:

```
@PapProperty   (highest)
   ↓ checked if not in property
@PapAttribute  (middle)
   ↓ checked if not in attribute
@PapInclude    (lowest)
```

First hit wins. The result is one flat `Map<String, Object>` of source name → value, with each name's value coming from exactly one source per the precedence above.

### 4.1 Precedence rules

| Collision | Resolution |
|---|---|
| `@PapProperty.key == @PapAttribute.attributeName` on same class | `@PapProperty` value used |
| `@PapProperty.key == @PapInclude.name` on same class | `@PapProperty` value used |
| `@PapAttribute.attributeName == @PapInclude.name` on same class | `@PapAttribute` value used |
| Two `@PapAttribute` fields, same `attributeName` | **compile-time error** |
| Two `@PapInclude`, same `name` | **compile-time error** |
| Two `@PapProperty`, same `key` | **compile-time error** |

### 4.2 Design implication — `@PapProperty` is authoritative, not a fallback

Earlier design revisions treated `@PapProperty` as a default that entity state overrode. v9 reverses this: declaring `@PapProperty(key = "X")` *guarantees* that the property value is used for source `X`, regardless of what the entity holds.

Practical guidance:
- Use `@PapProperty` for values that should not vary per instance: `propagate`, `resource_type_id`, environment-tag headers.
- Use `@PapAttribute` for values from the entity's own state: `code`, `name`, `description`.
- Use `@PapInclude` for values from related entities: `tenant_id`, `tenant_code`.
- Don't declare the same source name in multiple types — only the highest-precedence one contributes, the others are dead code.

### 4.3 Resolution at request time

For each source name a catalog map references, the SDK applies the precedence lookup.

- **Missing in all three sources:**
  - Path / headers / queryParams: throw `PapSdkException("No value for source 'X'")`.
  - Body: silently omitted.

### 4.4 Worked example

```java
@PapEntity(
    entity = "ResourceInstance",
    properties = {
        @PapProperty(key = "propagate",        value = "true"),
        @PapProperty(key = "resource_type_id", value = "102")
    }
)
public class Pipeline {
    @Id @PapAttribute(attributeName = "id")  private Integer id;
    @PapAttribute(attributeName = "code")     private String code;
    @PapAttribute(attributeName = "name")     private String name;

    @ManyToOne(fetch = FetchType.EAGER)
    @PapInclude(name = "tenant_id", attribute = "id")
    private Tenant tenant;
}
```

For `new Pipeline(id=42, code="P-001", name="first", tenant.id=7)`:

| Source name | Resolution | Value |
|---|---|---|
| `propagate` | `@PapProperty` (only source) | `"true"` |
| `resource_type_id` | `@PapProperty` (only source) | `"102"` |
| `id` | `@PapAttribute` (only source) | `42` |
| `code` | `@PapAttribute` (only source) | `"P-001"` |
| `name` | `@PapAttribute` (only source) | `"first"` |
| `tenant_id` | `@PapInclude` (only source) | `7` |

Now suppose the developer also adds `@PapProperty(key = "tenant_id", value = "999")`:

| Source name | Resolution | Value |
|---|---|---|
| `tenant_id` | `@PapProperty` wins over `@PapInclude` | `"999"` |

The include becomes dead code. No error, but the runtime can log at DEBUG when this happens to surface the override.

## 5. Mode resolution

For each captured change:

1. Per-operation declared in `@PapEntity.operationModes`.
2. Global default from `pap.sdk.mode`.
3. `SYNC`.

## 6. SDK architecture — packages and classes

Six Maven modules.

```
pep-sdk-parent
├── sdk-core                          ← shared kernel
│   └── com.example.pep.sdk.core
│       ├── annotation
│       │   ├── PapEntity                  class annotation
│       │   ├── PapAttribute               field annotation
│       │   ├── PapInclude                 field annotation (repeatable)
│       │   ├── PapIncludes                container for repeated PapInclude
│       │   ├── PapProperty                nested in PapEntity.properties
│       │   ├── PapOperationMode           nested in PapEntity.operationModes
│       │   └── CommunicationMode          enum SYNC | ASYNC
│       ├── model
│       │   ├── Operation                  CREATE | UPDATE | DELETE
│       │   ├── HttpMethod                 POST | PATCH | DELETE | GET
│       │   ├── OutboxStatus               PENDING | REJECTED_DATA | DEAD_LETTER
│       │   ├── AttributeAccessor          cached reflective field accessor
│       │   ├── IncludeAccessor            holds (name, dot-path)
│       │   ├── PapEntityDescriptor        per-entity runtime metadata (§7.1)
│       │   └── PapEntityChange            record passed listener → buffer
│       ├── catalog                        OperationSpec | EndpointSpec | PapCatalog
│       ├── registry                       PapEntityRegistry | MetadataLoader
│       ├── request                        PapRequest | EndpointResolver | PapRequestBuilder
│       └── exception                      PapException | PapRejectedException | PapUnavailableException | PapSdkException
│
├── sdk-processor                     ← compile-time validation + metadata
│   └── com.example.pep.sdk.processor
│       ├── PapAnnotationProcessor         entry point
│       ├── CompileTimeCatalog             hand-rolled JSON reader
│       └── DotPathValidator               walks each @PapInclude.attribute against the target type graph
│
├── sdk-client                        ← PapClient | PapRequestDecorator
├── sdk-sync                          ← ChangeBuffer | OutboxAppender | PapTransactionSynchronization
├── sdk-async                         ← PapOutboxEntry | PapOutboxRepository | DefaultOutboxAppender | PapOutboxConsumer
└── sdk-spring-boot-starter           ← PapSdkProperties | PapEntityListener | HibernateListenerRegistrar | Resilience4jFactory | PapSdkAutoConfiguration
```

### 6.1 Module dependency graph

```
sdk-spring-boot-starter ──► sdk-async ──► sdk-sync ──► sdk-client ──► sdk-core
                              │              │
                              └──────────────┴──► sdk-core

sdk-processor ──► sdk-core   (compile-time only)
```

## 7. Class-by-class reference (selected)

### 7.1 `PapEntityDescriptor`

Per-`@PapEntity` runtime metadata. Built once per class by `MetadataLoader` at startup; immutable.

```java
public final class PapEntityDescriptor {
    private final Class<?> entityClass;
    private final String papEntity;
    private final Map<String, String> properties;            // @PapEntity.properties — highest
    private final Map<Operation, CommunicationMode> operationModes;
    private final List<AttributeAccessor> directAttributes;  // @PapAttribute fields — middle
    private final List<IncludeAccessor> includes;            // @PapInclude entries — lowest
    private final AttributeAccessor idAttribute;
    private final CommunicationMode globalDefaultMode;

    /**
     * Builds the source map by precedence. Includes first (lowest), then attributes,
     * then properties — each successive put overwrites, so highest precedence wins.
     */
    public Map<String, Object> resolveSources(Object entity) {
        Map<String, Object> out = new LinkedHashMap<>();

        // Layer C (lowest): includes
        for (IncludeAccessor inc : includes) {
            Object value = inc.evaluate(entity);
            if (value != null) out.put(inc.name(), value);
        }

        // Layer B (middle): direct attributes — overrides includes
        for (AttributeAccessor a : directAttributes) {
            out.put(a.attributeName(), a.read(entity));
        }

        // Layer A (highest): @PapProperty — overrides attributes and includes
        properties.forEach(out::put);

        return out;
    }
}
```

Note the rename from `snapshot` to `resolveSources` — the method now does precedence resolution, not just a snapshot of entity state.

### 7.2 `PapAnnotationProcessor` — validation rules

Errors:

1. **Catalog membership.** `entity` must be a key in `pap-endpoints.json`.
2. **`@Id` field with `@PapAttribute`.**
3. **`@PapInclude` on a collection type.**
4. **`@PapInclude.attribute` dot-path resolves.** Each segment validated via `DotPathValidator` against the target `TypeElement`.
5. **Intra-type name collisions:**
   - Two `@PapAttribute` on one class with same `attributeName`.
   - Two `@PapInclude` (anywhere on the class) with same `name`.
   - Two `@PapProperty` with same `key`.
   - Two `@PapOperationMode` with same `operation`.
6. **Coverage rule.**

   > Every source name referenced by the catalog must be coverable by at least one of:
   > - a `@PapAttribute` field on this class,
   > - a `@PapProperty` key,
   > - a `@PapInclude.name` on this class.

Warnings:

- `@PapInclude` on a `FetchType.LAZY` field.
- Dot-path depth exceeds `max-depth`.
- **Cross-type same-name collision.** When `@PapProperty.key == @PapAttribute.attributeName`, `@PapProperty.key == @PapInclude.name`, or `@PapAttribute.attributeName == @PapInclude.name` on the same class, the processor warns: "X is supplied by both Y and Z; Y wins by precedence." Not an error — the developer might want the precedence behavior intentionally — but visible so accidents surface at build.

#### What's not validated

- Whether declarations are referenced by the catalog.
- Datatypes — surfaces as runtime PAP rejections.

## 8. Class interactions — sequence (CREATE example)

### 8.1 Sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant SVC as Service code
    participant JPA as Hibernate
    participant LIS as PapEntityListener
    participant REG as PapEntityRegistry
    participant DESC as PapEntityDescriptor
    participant BUF as ChangeBuffer
    participant TXN as TxnSynchronization
    participant BLD as PapRequestBuilder
    participant CLI as PapClient

    SVC->>JPA: repository.save(pipeline)
    JPA->>LIS: PostInsertEvent
    LIS->>REG: find(Pipeline.class)
    REG-->>LIS: descriptor
    LIS->>DESC: resolveSources(pipeline)
    Note over DESC: includes → attributes → properties<br/>(each overlays the previous; properties win)
    DESC-->>LIS: { tenant_id: 7, code: "P-001", name: "first",<br/>propagate: "true", resource_type_id: "102" }
    LIS->>BUF: append(change, descriptor)
    Note over JPA: commit phase
    JPA->>TXN: beforeCommit()
    TXN->>BUF: drain()
    TXN->>BLD: build(descriptor, change)
    BLD->>BLD: walk catalog maps → resolve wire shape from sources
    BLD-->>TXN: PapRequest
    TXN->>CLI: send(request)
    CLI-->>TXN: 2xx or throws
    JPA-->>SVC: commit complete (or rollback if threw)
```

### 8.2 Dispatch ordering guarantees

When one transaction touches multiple `@PapEntity` instances, the order the SDK issues HTTP calls to the PAP is determined entirely by two mechanisms working together:

1. **`ChangeBuffer` preserves insertion order.** It is backed by a `LinkedHashMap`; `drain()` returns entries in the order they were appended, never reordered.
2. **The dispatch loop is sequential and blocking.** `PapTransactionSynchronization.beforeCommit` iterates the drained list with a plain `for` loop, calling `papClient.send(...)` for each change. The next call is not issued until the current one returns or throws — there is no concurrency, no batching, no reordering at the dispatch layer.

So the dispatch order is exactly the buffer's insertion order, and the buffer's insertion order is exactly the order Hibernate's `PostInsertEvent` / `PostUpdateEvent` / `PostDeleteEvent` fire. That makes Hibernate's flush algorithm the actual source of truth for ordering, with two properties worth stating precisely:

**Within one operation type, default Hibernate behavior preserves call order.** If three entities are all being created in one transaction, Hibernate's insert action queue fires `PostInsertEvent` in the order `persist()`/`save()` was called — by default. This is what makes "Pipeline → Deployment → Monitor, in that literal code order, all CREATE" dispatch to the PAP in that same order, with no extra configuration.

**Across operation types, Hibernate reorders regardless of call order.** Flush always processes in the sequence inserts → updates → deletes, independent of which order your code issued them in. If a transaction creates Pipeline, updates Deployment, and deletes Monitor — even if that's the literal call order — Hibernate fires the events insert(Pipeline), then update(Deployment), then delete(Monitor) by operation-type grouping, which happens to match here, but would *not* match if the literal call order were update(Deployment), then create(Pipeline), then delete(Monitor) — Hibernate would still fire insert before update before delete, reordering relative to the code. **This reordering is not configurable and the SDK cannot prevent it** — any ordering requirement spanning mixed operation types needs to be enforced by the consuming application's design (e.g., separate transactions per operation type) rather than assumed from the SDK.

**`hibernate.order_inserts` / `hibernate.order_updates` break the within-operation-type guarantee.** These are opt-in Hibernate settings (off by default) that regroup the insert/update action queues *by table* to enable JDBC batching. If either is enabled, the order-preservation described above no longer holds — Hibernate fires events grouped by entity/table rather than by call order. `HibernateListenerRegistrar` checks `SessionFactoryOptions.isOrderInsertsEnabled()` / `isOrderUpdatesEnabled()` at startup and logs a warning if either is true, so this isn't discovered via a support ticket months later.

**Explicit flush in `beforeCommit` is required, not optional.** Spring's transaction manager calls `triggerBeforeCommit()` — where `PapTransactionSynchronization.beforeCommit()` runs — *before* `doCommit()`, and `doCommit()` is what triggers `JpaTransactionManager`'s own commit-time flush. Without forcing the flush explicitly as the first action of `beforeCommit()`, any change Hibernate defers (UPDATE via dirty-checking, DELETE, or CREATE under `SEQUENCE`/`TABLE` id generation rather than `IDENTITY`) has not had its SQL executed yet at the point the buffer is drained — meaning the listener has not fired and that change is silently absent from this dispatch. `IDENTITY`-strategy CREATE is the one case that works without this, because Hibernate forces the INSERT immediately at `persist()` time to obtain the generated id — which is why this could go unnoticed in testing limited to CREATE. The flush executes SQL inside the still-open, still-rollback-able transaction; it does not commit anything, so this has no bearing on the dual-write ordering discussed in §12.1.

**The guarantee terminates at "the order calls are issued."** The SDK can guarantee the sequence in which it sends HTTP requests to the PAP — including that call N+1 is never sent before call N's response is received. It cannot guarantee anything about how the PAP applies those calls internally once received. If the PAP's own backend is not strongly consistent (e.g., it processes writes asynchronously across workers), receive-order and apply-order may diverge on the PAP's side — that is a property of the PAP, outside what any client-side SDK can enforce.

### 8.3 Multi-target dispatch in one transaction

Nothing in the dispatch path assumes one transaction maps to one PAP entity type. Each `PapEntityChange` carries its own `entityClass`; on every iteration of the dispatch loop, `PapTransactionSynchronization.dispatch` looks up *that change's* descriptor independently via `registry.find(change.entityClass())`, and that descriptor's `papEntity` string drives an independent `catalog.endpointFor(...)` lookup. This means:

- **Several local entity classes can map to the same PAP entity.** `Pipeline`, `Deployment`, and `Monitor` can each independently declare `@PapEntity(entity = "ResourceInstance")`. Each is validated against the catalog independently at compile time (coverage rule checked per Java class), and each builds its own `PapRequest` from the same catalog entry at dispatch time. There is no 1:1 assumption anywhere in the registry or catalog lookup that would need relaxing for this to work — it already works.
- **A transaction can mix targets freely.** If the same transaction also creates an entity `A` with `@PapEntity(entity = "new_pap_table")`, dispatch proceeds through the same loop, in the same insertion order — `A`'s turn simply resolves against a different catalog entry and issues a POST to a different path. The ordering guarantees in §8.2 apply identically regardless of how many distinct PAP entities are involved.
- **No batching across same-target dispatches.** Three changes targeting the same catalog entity still produce three independent HTTP calls; the SDK does not combine them into one request even when the path is identical. If the PAP needs to distinguish what "kind" of `ResourceInstance` each represents, that's expressible today via `@PapProperty` (e.g., a different `resource_type_id` per local entity class) — no SDK change required.
- **Divergence attribution remains per-entity.** If a multi-target transaction fails partway (§12.1), the `STL_PEP_DIVERGENCE` rows written on rollback are keyed by each individual entity's `entity_type` (its `papEntity` string) and `entity_id` — a transaction touching both `ResourceInstance` and `new_pap_table` produces correctly-attributed divergence rows for whichever entities had already received a successful PAP response, regardless of how many distinct PAP entities were involved.



## 9. Generated metadata — `META-INF/pep-sdk/metadata.json`

### 9.1 One file per Maven module

```json
{
  "version": 1,
  "entities": [
    {
      "entityClass": "com.example.demo.Pipeline",
      "papEntity": "ResourceInstance",
      "idField": "id",
      "properties":     { "propagate": "true", "resource_type_id": "102" },
      "operationModes": { "DELETE": "ASYNC" },
      "attributes": [
        { "fieldName": "id",          "attributeName": "id" },
        { "fieldName": "code",        "attributeName": "code" },
        { "fieldName": "name",        "attributeName": "name" },
        { "fieldName": "description", "attributeName": "description" }
      ],
      "includes": [
        { "fieldName": "tenant", "name": "tenant_id", "attribute": "id" }
      ]
    }
  ]
}
```

### 9.2 Multi-module behavior

Each module produces its own file. `MetadataLoader.load` uses `classLoader.getResources(...)` and merges. Duplicate `entityClass` across modules fails startup.

### 9.3 Included types are not top-level entries

`Tenant` does not appear unless it independently carries `@PapEntity`.

## 10. Schema — `STL_PEP_OUTBOX`

```sql
CREATE TABLE STL_PEP_OUTBOX (
    id              BIGSERIAL    PRIMARY KEY,
    entity_type     VARCHAR(255) NOT NULL,
    operation       VARCHAR(50)  NOT NULL,
    payload         TEXT         NOT NULL,
    header          TEXT         NOT NULL,
    path_variable   TEXT         NOT NULL,
    request_param   TEXT         NOT NULL,
    attempt_count   INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    status          VARCHAR(16)  NOT NULL
);
CREATE INDEX idx_stl_pep_outbox_status_created ON STL_PEP_OUTBOX (status, created_at);
CREATE INDEX idx_stl_pep_outbox_entity_type    ON STL_PEP_OUTBOX (entity_type);
```

Unchanged. Used by ASYNC dispatch and, if `pap.sdk.sync.auto-compensate` is enabled, by SYNC's optional compensation attempts.

### 10.1 `STL_PEP_DIVERGENCE`

Separate table for SYNC's dual-write failure detection (§12.1) — deliberately distinct from the outbox, since its lifecycle is "flagged for review" rather than "will be auto-processed." Schema given in full at §12.1.

## 11. Configuration

```yaml
pap:
  sdk:
    enabled: true
    base-url: https://pap.internal.example.com
    mode: SYNC
    include:
      max-depth: 5
    retry:
      max-attempts: 3
      initial-backoff: 200ms
      max-backoff: 5s
    circuit-breaker:
      failure-rate-threshold: 50
      sliding-window-size: 20
      wait-duration-in-open-state: 30s
    timeout:
      connect: 2s
      read: 10s
    outbox:
      poll-interval: 1s
      batch-size: 50
      max-attempts: 10
```

## 12. Error handling

```
PapException (abstract)
 ├─ PapRejectedException    4xx; statusCode + reason
 ├─ PapUnavailableException 5xx / transport / circuit open
 └─ PapSdkException         SDK bug / misconfiguration
```

| Scenario | SYNC | ASYNC |
|---|---|---|
| PAP 4xx | `PapRejectedException` → rollback | row → `REJECTED_DATA` |
| 5xx / transport (after retries) | `PapUnavailableException` → rollback | `attempt_count++`; eventual `DEAD_LETTER` |
| Circuit open | fail fast → rollback | consumer pauses; no attempt++ |
| Source missing (path/header/query) | `PapSdkException` → rollback | `PapSdkException` propagates |
| Source missing (body) | silently omitted | silently omitted |
| `@PapInclude.attribute` field doesn't exist | **compile-time error** (or startup `PapSdkException` if compile-time slipped) | same |
| `@PapInclude` dot-path hits null mid-walk | source-map entry absent | same |
| Datatype mismatch | surfaces as PAP rejection (4xx) | same |
| Cross-type name collision | precedence rule (`@PapProperty > @PapAttribute > @PapInclude`); compile-time warning | same |
| Intra-type name collision | compile-time error | compile-time error |
| **PAP write OK, then local transaction does not commit** | **divergence row written PENDING at dispatch, resolved at completion (§12.1)** | n/a — PAP not called in caller thread |

### 12.1 SYNC dual-write failure — `STL_PEP_DIVERGENCE`

The SYNC path dispatches in `beforeCommit`: Hibernate has flushed but the DB transaction is not yet committed. If the PAP call succeeds and the local transaction then fails to commit — a deferred constraint violation, connection loss, a later entity in the same dispatch loop being rejected and vetoing the whole transaction — the PAP holds a record the service database does not. There is no in-process intercept point that eliminates this without distributed transactions (2PC), which the PAP does not support, so the SDK detects and durably records rather than silently auto-repairing.

**Schema.**

```sql
CREATE SEQUENCE pap_divergence_transaction_seq;

CREATE TABLE STL_PEP_DIVERGENCE (
    id              SERIAL       PRIMARY KEY,
    transaction_id  INTEGER      NOT NULL,
    entity_type     VARCHAR(50)  NOT NULL,
    operation       VARCHAR(50)  NOT NULL,
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    status          VARCHAR(15)  NOT NULL,   -- PENDING | REJECTED_DATA
    error           JSONB
);
CREATE INDEX idx_stl_pep_divergence_transaction ON STL_PEP_DIVERGENCE (transaction_id);
CREATE INDEX idx_stl_pep_divergence_status      ON STL_PEP_DIVERGENCE (status);
```

`transaction_id` is one sequence value per `beforeCommit()` invocation — every row written during that invocation shares it, so resolving an entire multi-entity transaction's rows is one bulk statement keyed on `transaction_id`, not a row-by-row walk.

**Mechanism — rows are written PENDING immediately, before the local transaction's outcome is known, not only reactively on rollback.**

1. `beforeCommit()` draws one `transaction_id` from `pap_divergence_transaction_seq`, then dispatches each buffered change in order.
2. Each time a PAP call returns success, the SDK immediately writes a row: `transaction_id`, `entity_type` (the descriptor's `papEntity`), `operation`, `payload` (see below), `created_at = now()`, `status = 'PENDING'`. This insert runs in its **own transaction (`REQUIRES_NEW`)**, committed immediately — it is durable from that instant, independent of whatever the surrounding business transaction does next. A PAP rejection (4xx) throws and is never recorded — nothing was written to the PAP for that entity, so there's nothing to flag.
3. `afterCompletion(COMMITTED)`: `DELETE FROM STL_PEP_DIVERGENCE WHERE transaction_id = ? AND status = 'PENDING'`. Everything landed on both sides — these were never real divergences, just provisional records of an outcome that turned out fine.
4. `afterCompletion(ROLLED_BACK` or `UNKNOWN)`: `UPDATE STL_PEP_DIVERGENCE SET status = 'REJECTED_DATA', error = ? WHERE transaction_id = ? AND status = 'PENDING'`. These rows are now confirmed: the PAP has them, the local DB does not. `error` records why the *local* transaction failed to commit — note this is distinct from a PAP-side rejection (which never produces a row at all, per step 2); `REJECTED_DATA` here means "this PAP record should now be rejected/removed," not "the PAP rejected it." `afterCompletion(int status)` carries no causing exception, so `error` content is necessarily a generic structured note (e.g. `{"reason": "LOCAL_TRANSACTION_NOT_COMMITTED", ...}`) rather than the literal local exception, unless a richer mechanism is later substituted (Spring Framework 6.1's `TransactionExecutionListener` exposes the causing `Throwable` directly and is worth evaluating, but its exact API wasn't verified against real Spring jars in this sandbox).

**Payload contents — deliberately broader than the table comment's "body."** `payload` holds the *full* resolved request shape — body, headers, path variables, and query params together — not only the wire body. Reason: the catalog's DELETE operation typically defines an empty body; if `payload` captured only the body, a DELETE's divergence row would carry no identifying information at all, leaving a support engineer with nothing to act on. Capturing everything means whichever field the catalog used to carry the id (path variable, header, or body) is always present in the row.

**Why PENDING-immediately is better than writing only on rollback.** An earlier revision of this mechanism wrote rows only reactively, inside `afterCompletion(ROLLED_BACK)`. Writing PENDING immediately at dispatch time closes most of the residual crash window that approach left open: if the JVM dies between a PAP call succeeding and the local transaction's outcome being determined, a durable PENDING row already exists — a reconciliation sweep can catch PENDING rows older than some threshold and treat them as needing investigation, even without ever reaching `afterCompletion`. The trade-off is an added cost on the happy path: every successful SYNC dispatch now does one extra INSERT (the PENDING row) and, on commit, one extra bulk DELETE — both of which the reactive-only design avoided. This is a deliberate trade of a small constant cost per dispatch for substantially better crash safety.

**Multi-entity transactions.** The mechanism handles every combination uniformly because resolution operates on `transaction_id`, not on any assumption about how many entities were involved:

| Scenario (3 entities, fail-fast sequential dispatch) | What lands in `STL_PEP_DIVERGENCE` |
|---|---|
| A, B succeed at PAP; C is rejected (4xx) or unavailable | PENDING rows for A and B only (C never wrote to the PAP). Local DB rolls back. `afterCompletion(ROLLED_BACK)` flips A and B to `REJECTED_DATA`. |
| A, B, C all succeed at PAP; local commit itself then fails | PENDING rows for A, B, C, all sharing one `transaction_id`. `afterCompletion(ROLLED_BACK)` flips all three to `REJECTED_DATA` in one bulk `UPDATE`. |
| A, B, C all succeed at PAP; local commit succeeds | PENDING rows for A, B, C, then `afterCompletion(COMMITTED)` deletes all three in one bulk `DELETE`. Net: no rows remain. |
| "2 of 3 committed locally, 1 didn't" | **Structurally impossible** under one `@Transactional` boundary — the local commit is one atomic JDBC operation over every entity's already-issued SQL. If observed, the actual cause is multiple separate transaction boundaries, not a partial commit of one transaction. |

**Residual window, now narrower.** A crash between a PAP call succeeding and the PENDING row's own `REQUIRES_NEW` insert committing is still possible but extremely small (one local INSERT). Closing it completely would require an intent record written *before* the PAP call — an opt-in hardening (`pap.sdk.sync.intent-log`, default off) given its cost (an extra DB round-trip on every dispatch) against an already-narrow window.

**No automatic compensation.** Calling the PAP again to reverse a confirmed write is not built into this mechanism. Its success would depend on the same PAP availability that may have been part of the original problem, and for policy data, an automated reversal cannot undo any downstream effect the PAP's original write may have already triggered. `REJECTED_DATA` rows are surfaced for a human to act on, not auto-resolved.

## 13. Resilience

Resilience4j Retry + CircuitBreaker wrap every PAP call.

- 4xx: not retried, not counted by breaker.
- 5xx / transport: retried up to `retry.max-attempts`; counted by breaker.
- Circuit open: SYNC fails fast; consumer pauses this tick.

**No idempotency keys.** PAP must dedupe naturally.

## 14. Open Issues

1. **Duplicate dispatch without idempotency.** PAP must dedupe.
2. **No `last_error` on outbox.**
3. **`entity_id` not denormalized.**
4. **Datatype validation deferred.**
5. **HTTP method implicit per operation name.**
6. **`@PapInclude` and lazy loading.** Warn at startup today.
7. **`@PapInclude` collections.** Out of scope.
8. **Per-call dynamic values.** `@PapProperty` is static and authoritative; `@PapAttribute`/`@PapInclude` reach into the entity tree but lose to `@PapProperty` on collision. Genuinely per-call values (correlation IDs, one-off flags) still have no clean path. Three deferred options: Spring MVC interceptor, `@PapSync` aspect, explicit `PapRequestContext.put`.
9. **Response retrieval.** SYNC and ASYNC are fire-and-forget.
10. **Divergence-detection residual crash window (§12.1).** Narrowed by writing PENDING immediately at dispatch rather than reactively on rollback; fully closing it requires the opt-in intent-log (extra DB round-trip per dispatch). Default off.
11. **`STL_PEP_DIVERGENCE.error` content is limited by `afterCompletion(int status)`.** No causing exception is available through this API — only a generic structured note. `TransactionExecutionListener` (Spring Framework 6.1+) exposes the actual `Throwable` and is worth evaluating, but wasn't verified against real Spring jars in this sandbox.
12. **Cross-operation-type ordering not enforceable from listeners.** Hibernate's insert→update→delete flush grouping is not configurable; a transaction mixing CREATE/UPDATE/DELETE across entities cannot have its literal code order preserved by the SDK. Recovering true call order would require AOP at the repository call site (capturing a sequence number before Hibernate's flush reorders anything) — not implemented; documented as a known limitation (§8.2).
13. **PAP-side apply-order is outside the SDK's guarantee.** The SDK guarantees the order it *issues* calls (§8.2) but cannot guarantee the order a non-strongly-consistent PAP backend applies them after receipt.
14. **Transaction granularity — open decision.** With one local transaction spanning multiple entities, a PAP rejection partway through the dispatch loop rolls back *everything* locally, even entities whose PAP call already succeeded (§12.1 "Case 1" — handled via divergence rows, not avoided). An alternative — each entity's local write and PAP call as its own separate transaction — eliminates that divergence window entirely, at the cost of giving up "these N entities are created together or not at all" as a guarantee. Not implemented; would need to be an explicit opt-in (e.g. a `transactionScope` setting on `@PapEntity`) rather than a default, since it changes what wrapping several entities in one `@Transactional` method means for any entity opted in.

## 15. Lifecycle: compile time → startup → runtime

### 15.1 Compile time

The annotation processor (`PapAnnotationProcessor`, in `sdk-processor`) runs as part of `javac` on every module containing `@PapEntity` classes. For each one:

1. Reads the bundled compile-time catalog copy (`CompileTimeCatalog`, hand-rolled JSON parser, no Jackson) and checks `entity` is a known PAP entity.
2. Validates the rules in §7.2 — `@Id` + `@PapAttribute` present, no `@PapInclude` on a collection, `@PapInclude.attribute` dot-paths resolve via `DotPathValidator`, intra-type name collisions are errors, cross-type collisions are warnings, the coverage rule (every catalog-referenced source name is reachable from some `@PapAttribute`/`@PapProperty`/`@PapInclude`).
3. On success, emits one `metadata.json` per module to `META-INF/pep-sdk/metadata.json` in that module's compiled output — a `(fieldName, attributeName)` list for `@PapAttribute`, a `(fieldName, name, attribute)` list for `@PapInclude`, the `@PapProperty` map, and the `@PapOperationMode` map.

Nothing here touches a database or makes a network call. This phase exists entirely to catch mistakes — a typo'd catalog entity name, a dot-path that doesn't resolve, two fields claiming the same source name — before the application ever runs.

### 15.2 Application startup

When the Spring context initializes, `PapSdkAutoConfiguration` wires the SDK in this order (driven by Spring's normal bean-dependency resolution, not an explicit phase list, but this is the effective sequence):

1. **`MetadataLoader.load(classLoader)`** calls `classLoader.getResources("META-INF/pep-sdk/metadata.json")`, reads every copy on the classpath (one per module that had `@PapEntity` classes at compile time), and builds one `PapEntityDescriptor` per entity class — resolving each `@PapInclude`'s dot-path to a pre-resolved `Field[]` chain via reflection at this point (a second, runtime-side check behind the compile-time one; see §3.4). The result is the `PapEntityRegistry`, keyed by `Class<?>`.
2. **`PapCatalog`** loads the runtime catalog copy (`pap-endpoints.json`) from `sdk-core`'s resources.
3. **`HibernateListenerRegistrar`** unwraps the `EntityManagerFactory` to a Hibernate `SessionFactoryImplementor`, gets its `EventListenerRegistry`, and appends `PapEntityListener` to the `POST_INSERT`, `POST_UPDATE`, and `POST_DELETE` event types (see §15.3 for why these three). It also checks `SessionFactoryOptions.isOrderInsertsEnabled()`/`isOrderUpdatesEnabled()` and warns if either is true, since both break the dispatch-order guarantee (§8.2).
4. **`PapClient`**, **`Retry`**, and **`CircuitBreaker`** are constructed from `PapSdkProperties` — the `RestClient` with its connect/read timeouts, the retry policy, the circuit breaker thresholds.
5. **`DivergenceRecorder`** and **`OutboxAppender`** beans are constructed (the SPI implementations living in `sdk-async`, backed by JPA repositories whose entities — `PapDivergenceEntry`, `PapOutboxEntry` — are picked up by the starter's `@EntityScan`/`@EnableJpaRepositories`).
6. **`PapTransactionSynchronization`** is constructed, taking the registry, request builder, client, outbox appender, divergence recorder, and a `@PersistenceContext`-injected `EntityManager` (the shared transaction-aware proxy — safe to hold in a singleton bean; each call routes to whichever `EntityManager` is bound to the currently active transaction).
7. **`PapEntityListener`** is constructed from the registry and the synchronization.
8. The outbox poller (`PapOutboxConsumer` wrapped by `OutboxScheduler`'s `@Scheduled` method) starts running on its own timer, independent of any request.

If any of this fails — most commonly a missing `pap.sdk.base-url`, or `MetadataLoader` finding a duplicate entity class across modules, or a `@PapInclude` field that somehow didn't resolve despite passing compile-time validation — the application fails to start rather than running with a half-built registry.

### 15.3 What events does `PapEntityListener` listen to?

Three Hibernate event types, via three listener interfaces it implements: `PostInsertEventListener` (event `POST_INSERT`), `PostUpdateEventListener` (event `POST_UPDATE`), and `PostDeleteEventListener` (event `POST_DELETE`). All three fire *after* Hibernate has executed the corresponding SQL against the open JDBC connection — not before, and not after commit. This timing is exactly what makes capture possible: the listener needs the entity's persisted state (including, for `IDENTITY`-strategy ids, the generated id itself) to build the source map, and that state only exists once the SQL has actually run.

The listener does not implement `PreInsertEventListener`/`PreUpdateEventListener`/`PreDeleteEventListener` — there's no need to intercept before the SQL runs, since the SDK's veto mechanism operates at the transaction level (`beforeCommit`, §15.4), not at the level of individual SQL statements.

### 15.4 How does `TransactionSynchronization` come into play?

Two distinct things happen, in two different classes, both built on Spring's transaction-synchronization machinery:

- **`PapEntityListener`**, on each Hibernate event, calls `TransactionSynchronizationManager.isSynchronizationActive()` to confirm a transaction is open (throwing `PapSdkException` if not — `@Transactional` is required), then `getResource(BUFFER_KEY)` to find the `ChangeBuffer` already bound to this transaction, or `bindResource(...)` a fresh one plus `registerSynchronization(papTxnSync)` on the *first* capture in this transaction. `TransactionSynchronizationManager`'s resource map is a `ThreadLocal`, so this buffer is automatically isolated per transaction with no locking required.
- **`PapTransactionSynchronization`** *is* the registered `TransactionSynchronization` — it implements the interface directly (not via `@TransactionalEventListener`, a decision discussed and kept for this correctness-sensitive code; see the earlier conversation on that trade-off). Spring calls its `beforeCommit(boolean)` before the underlying JPA commit, and `afterCompletion(int status)` once the transaction has fully finished (committed or rolled back).

So: the listener's job is populating a transaction-scoped buffer and registering the callback exactly once; the synchronization's job is draining that buffer at the right moment and resolving divergence rows once the outcome is known.

### 15.5 Application running — the request-driven path

For a single request that creates, updates, or deletes one or more `@PapEntity` instances inside one `@Transactional` method:

1. Service code calls `repository.save(...)` / `repository.delete(...)`. For `IDENTITY`-strategy ids this issues the INSERT immediately; for everything else (`UPDATE` via dirty checking, `DELETE`, non-`IDENTITY` `CREATE`), Hibernate defers the SQL.
2. `PapTransactionSynchronization.beforeCommit()` runs `entityManager.flush()` first — required, not optional, given Spring's phase ordering (§8.2) — forcing every deferred action to execute and fire its listener now, if it hasn't already.
3. Each `PostInsertEvent`/`PostUpdateEvent`/`PostDeleteEvent` reaches `PapEntityListener`, which resolves the entity's `PapEntityDescriptor` from the registry, calls `descriptor.resolveSources(entity)` (precedence: `@PapProperty` > `@PapAttribute` > `@PapInclude`, §4), and appends a `PapEntityChange` to the transaction-scoped `ChangeBuffer`.
4. Back in `beforeCommit()`: draws one `transaction_id`, drains the buffer (insertion order — §8.2), and for each change either dispatches synchronously (SYNC: builds the request, calls the PAP, writes a `PENDING` divergence row on success, throws on rejection) or appends to the outbox (ASYNC).
5. If `beforeCommit()` returned without throwing, Spring proceeds to the actual commit. `afterCompletion(COMMITTED)` deletes the transaction's `PENDING` rows (nothing diverged) and unbinds the buffer. If something threw — a PAP rejection, or the commit itself failing — `afterCompletion(ROLLED_BACK)` flips the transaction's `PENDING` rows to `REJECTED_DATA` instead.

This entire sequence is driven by the request; nothing happens outside of it for SYNC entities. The ASYNC outbox consumer is the one part of the runtime system that is *not* request-driven — it polls on its own fixed schedule (`pap.sdk.outbox.poll-interval`), picking up whatever ASYNC rows exist regardless of which request created them, independently claiming and dispatching batches until none remain.
