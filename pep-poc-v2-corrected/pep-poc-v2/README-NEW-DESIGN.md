# PEP SDK — Three-Transaction Design (v2 POC)

This POC implements the **three-transaction** synchronization model on top of the existing
PEP SDK architecture. It supersedes the original single-`DivergenceRecorder` design.

> **Build note:** This environment has no access to a Maven repository (Maven Central and all
> mirrors return HTTP 403; only github/npm/pypi/crates/ubuntu are reachable), so `mvn compile`
> cannot run here. The same constraint applied to the original POC — these modules are built in
> your own environment. The sources are complete and reference-consistent; build locally with
> `mvn clean install`.

---

## What changed vs the original POC

| Area | Original | New (this POC) |
|------|----------|----------------|
| Divergence rows | One PENDING row per entity, **deleted** on commit / flipped to REJECTED_DATA on rollback | One **permanent** row per call: `SUCCESS` or `FAILED` (full audit trail) |
| Transaction state | Implicit (presence/absence of divergence rows) | Explicit **`STL_PEP_TRANSACTION`** row: `PENDING → SUCCESS | FAILED` |
| PAP dispatch | Per-entity inside `beforeCommit`, each with its own `REQUIRES_NEW` divergence insert | One **TX2** (`REQUIRES_NEW`) batches all PAP calls + all audit inserts |
| Failure signaling | Exception thrown from dispatch | TX2 **returns a result**; caller throws afterward (so TX2's audit commit survives) |
| Resolution | `afterCompletion` deletes/flips divergence rows | **TX3** (`REQUIRES_NEW`) sets final transaction status |
| DB round-trips | ~N+2 | 3 (constant) |

---

## The three transactions

```
TX1  Business transaction (the caller's @Transactional method)
     • entities saved; Hibernate listener buffers each change
     • at beforeCommit (TX1 still open) → invoke TX2

TX2  PapTransactionService.invokeAndRecord(...)   [REQUIRES_NEW]
     • INSERT STL_PEP_TRANSACTION (PENDING)
     • for each change, in order:
         – if a previous call failed → record FAILED ("NOT_ATTEMPTED"), skip PAP
         – else call PAP: SUCCESS row (with response) or FAILED row (with reason, then stop)
     • batch INSERT all STL_PEP_DIVERGENCE rows
     • if any failed → mark transaction FAILED
     • COMMIT, then RETURN PapInvocationResult  (never throws on PAP failure)

   caller (beforeCommit):
     • result.failed()  → throw PapTransactionRolledBackException ⇒ TX1 rolls back
     • result.success() → bind transactionId; TX1 proceeds to commit

TX3  afterCompletion(status)                       [REQUIRES_NEW]
     • only if a transactionId was bound (i.e. TX2 succeeded):
         – COMMITTED   → markSuccess: PENDING → SUCCESS
         – ROLLED_BACK → markFailed : PENDING → FAILED  (commit failed despite PAP success)
```

### Why TX2 returns instead of throws (critical)

A `REQUIRES_NEW` method that throws has **its own transaction rolled back** by Spring — which
would erase the audit trail TX2 just wrote. So TX2 commits normally and returns a
`PapInvocationResult`; the orchestrator throws **after** TX2 has committed, purely to roll TX1
back. By then the `STL_PEP_TRANSACTION` (FAILED) and `STL_PEP_DIVERGENCE` rows are durable.

---

## Outcome matrix

| Case | PAP calls | TX1 commit | `STL_PEP_TRANSACTION` | `STL_PEP_DIVERGENCE` | Local data |
|------|-----------|-----------|----------------------|----------------------|-----------|
| All succeed | all SUCCESS | commits | **SUCCESS** (TX3) | all SUCCESS | persisted |
| PAP fails on entity *k* | 1..k-1 SUCCESS, k FAILED, rest NOT_ATTEMPTED | forced rollback | **FAILED** (TX2) | mixed SUCCESS/FAILED | rolled back |
| PAP all succeed, commit fails | all SUCCESS | fails | **FAILED** (TX3) | all SUCCESS | rolled back (diverged at PAP) |
| TX1 SQL error before beforeCommit | none | rolls back | *(no row created)* | *(none)* | rolled back |

The last row is intentional: a pre-commit SQL/business failure is the service application's
concern, not the SDK's — TX2 never runs, so no audit rows exist.

---

## New / changed files

**sdk-sync**
- `PapTransaction.java` — `STL_PEP_TRANSACTION` entity (PENDING/SUCCESS/FAILED).
- `PapTransactionRepository.java` — JPA repo for the transaction table.
- `PapDivergenceEntry.java` — now `SUCCESS`/`FAILED` + `response`, with `success(...)`/`failed(...)` factories.
- `PapDivergenceRepository.java` — write-once; delete/mark queries removed.
- `PapInvocationResult.java` — TX2's returned outcome (txId, failed, reason).
- `PapTransactionService.java` — **TX2 + TX3** logic.
- `PapTransactionSynchronization.java` — orchestrator (beforeCommit → TX2, afterCompletion → TX3).
- `DivergenceRecorder.java` — **removed**.

**sdk-core**
- `exception/PapTransactionRolledBackException.java` — marker to force TX1 rollback.

**sdk-client**
- `PapClient.send(...)` — now **returns the PAP response body** (captured into the divergence row).

**sdk-spring-boot-starter**
- `PapSdkAutoConfiguration.java` — wires `PapTransactionService` + repos; new sync constructor.

**demo-service**
- `db/migration/V2__pep_divergence.sql` — adds `STL_PEP_TRANSACTION` + `pep_transaction_seq`; divergence table gains `response`, FKs to the transaction table.
- `TransactionController.java` — `GET /transactions` to inspect `STL_PEP_TRANSACTION`.

---

## Inspecting a run (once built & deployed in your env)

```
POST /resources/ordered     # three CREATEs in one TX
GET  /transactions          # STL_PEP_TRANSACTION rows (PENDING/SUCCESS/FAILED)
GET  /divergence            # STL_PEP_DIVERGENCE rows (per-call SUCCESS/FAILED + response)
```

To exercise the failure path, point `pap.sdk.base-url` at a PAP stub that 4xx/5xx-es on a chosen
entity and observe: transaction `FAILED`, the failing call `FAILED`, later calls `NOT_ATTEMPTED`,
earlier calls `SUCCESS`, and the local rows rolled back.
