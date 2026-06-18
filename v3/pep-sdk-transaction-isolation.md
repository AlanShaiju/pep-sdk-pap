# Transaction Isolation: Why Divergence Rows Survive Rollback

## The Scenario

```
Timeline:
  T1. Entity Manager FLUSH executes INSERT Pipeline, Deployment, Monitor SQL
  T2. beforeCommit() loop: DISPATCH Pipeline -> PAP succeeds
  T3. recordPending(41, ...) called -> INSERTS PENDING row
  T4. beforeCommit() loop: DISPATCH Deployment -> PAP succeeds
  T5. recordPending(41, ...) called -> INSERTS PENDING row
  T6. beforeCommit() loop: DISPATCH Monitor -> PAP succeeds
  T7. recordPending(41, ...) called -> INSERTS PENDING row
  T8. beforeCommit() returns successfully
  T9. Spring calls JDBC commit on business TX
  T10. JDBC commit fails: constraint violation detected
  T11. Spring triggers ROLLBACK of business TX
  T12. All Pipeline/Deployment/Monitor INSERTs are rolled back
  T13. BUT the 3 PENDING rows survive!
```

## Why? Transaction Isolation via REQUIRES_NEW

When `divergenceRecorder.recordPending()` is invoked at T3, T5, T7:

### Before recordPending() call (still in business TX context)
```
TransactionManager state:
├─ Business TX: OPEN
│  └─ Hibernation INSERT statements queued
└─ No other active TX
```

### During recordPending() execution (REQUIRES_NEW kicks in)
```
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void recordPending(...) {
    // Spring intercepts this method call
    // Action 1: SUSPEND the business TX
    //   - Save its state (open JDBC connection, pending INSERTs)
    //   - Temporarily set it aside
    
    // Action 2: Open a NEW, independent transaction
    //   - New JDBC connection from pool
    //   - New transaction context
    
    // Action 3: Execute the INSERT
    //   INSERT INTO STL_PEP_DIVERGENCE (transaction_id, entity_type, operation, payload, status)
    //   VALUES (41, 'ResourceInstance', 'CREATE', '{...}', 'PENDING')
    
    // Action 4: COMMIT this new transaction IMMEDIATELY
    //   - New TX committed to Postgres
    //   - Row is DURABLE
    //   - Connection returned to pool
    
    // Action 5: RESUME the original business TX
    //   - Restore its state
    //   - Continue executing in its context
}
```

### After recordPending() returns (back in business TX context)
```
TransactionManager state:
├─ Business TX: OPEN (RESUMED, state intact)
│  └─ Still has Pipeline/Deployment/Monitor INSERT statements queued
└─ Divergence TX: COMMITTED (durable, separate)
   └─ PENDING row #1 now exists in Postgres
```

## Two Independent Transactions

```
Postgres database perspective:

┌─────────────────────────────────────────────────────────────────────┐
│                         BUSINESS TRANSACTION                         │
│  (Spring-managed, started at @Transactional method entry)           │
│                                                                      │
│  T1: FLUSH → INSERT INTO pipeline VALUES (...)                      │
│  T2: FLUSH → INSERT INTO deployment VALUES (...)                    │
│  T3: FLUSH → INSERT INTO monitor VALUES (...)                       │
│  ...............                                                    │
│  T9: COMMIT ← but FAILS: constraint violation                       │
│  T11: ROLLBACK ← all INSERTs rolled back, gone from database        │
│                                                                      │
│  Result: pipeline table empty, deployment table empty, etc.         │
└─────────────────────────────────────────────────────────────────────┘
                    ↓ (not part of this TX)
┌─────────────────────────────────────────────────────────────────────┐
│              DIVERGENCE TRANSACTION #1 (REQUIRES_NEW)               │
│  (Completely independent, opened by Spring while business TX paused)│
│                                                                      │
│  T3: INSERT INTO STL_PEP_DIVERGENCE (txId=41, status=PENDING) ✓    │
│  T3: COMMIT ✓ (durable, separate from business TX fate)            │
│                                                                      │
│  Result: divergence row EXISTS in database                          │
└─────────────────────────────────────────────────────────────────────┘

(Same for divergence TX #2 and #3, each with their own COMMIT)
```

## The REQUIRES_NEW Isolation Guarantee

```
Spring's TransactionPropagation.REQUIRES_NEW semantics:

REQUIRES_NEW = "Always create a new transaction for this method"

If called from within an existing TX:
  1. Suspend the existing TX (save its state, release lock/connection)
  2. Create a new TX (get fresh connection from pool)
  3. Execute the method in the new TX's context
  4. Commit the new TX (durable, released to Postgres)
  5. Resume the suspended TX (restore its state, same lock/connection)
     
If the suspended TX later rolls back:
  ✓ The new TX is unaffected (already committed)
  ✓ Its changes are durable (Postgres sees them)
  ✗ The suspended TX's changes are rolled back (never committed)
```

## Code Evidence

From `DivergenceRecorder.java`:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void recordPending(int transactionId, String entityType, String operation, Map<String, Object> payload) {
    try {
        String json = objectMapper.writeValueAsString(payload);
        repository.save(new PapDivergenceEntry(...));
        // ↑ At this point, Spring has suspended the business TX
        // and opened a separate TX for this save. After save completes,
        // this new TX is COMMITTED before returning.
    } catch (Exception e) {
        throw new PapSdkException(...);
    }
}
```

Javadoc in the class confirms this:

```java
/**
 * Writes and resolves STL_PEP_DIVERGENCE rows. Every method is REQUIRES_NEW:
 *
 * <ul>
 *   <li>{@link #recordPending} runs while the business transaction is mid-beforeCommit
 *       — it must commit independently of it, since that transaction's fate is 
 *       exactly what is still unknown.</li>
 * </ul>
 */
```

## The Crucial Timing

```
Timeline with TX boundaries:

JDBC Level:
─────────────────────────────────────────────────────────────────────

conn1 (business TX):
  ├─ T0: BEGIN
  ├─ T1: INSERT INTO pipeline...
  ├─ T2: INSERT INTO deployment...
  ├─ T3: INSERT INTO monitor...
  ├─ T8: (beforeCommit finishes, waiting for COMMIT instruction)
  ├─ T9: COMMIT   ← Spring issues this
  └─ T11: ROLLBACK ← Postgres rolls back due to constraint violation
                      ALL INSERTs undone

conn2 (divergence TX #1, via REQUIRES_NEW):
  ├─ T3: BEGIN
  ├─ T3: INSERT INTO STL_PEP_DIVERGENCE status=PENDING...
  └─ T3: COMMIT   ← Completes immediately, before returning to business TX
                      This row is PERMANENT in Postgres

conn3 (divergence TX #2, via REQUIRES_NEW):
  ├─ T5: BEGIN
  ├─ T5: INSERT INTO STL_PEP_DIVERGENCE status=PENDING...
  └─ T5: COMMIT   ← Completely independent of conn1's eventual ROLLBACK

conn4 (divergence TX #3, via REQUIRES_NEW):
  ├─ T7: BEGIN
  ├─ T7: INSERT INTO STL_PEP_DIVERGENCE status=PENDING...
  └─ T7: COMMIT   ← Durable before business TX even attempts to commit

─────────────────────────────────────────────────────────────────────

Final state after T11 ROLLBACK:
  ✗ pipeline table: EMPTY (rolled back)
  ✗ deployment table: EMPTY (rolled back)
  ✗ monitor table: EMPTY (rolled back)
  ✓ STL_PEP_DIVERGENCE: 3 PENDING rows (survived, separate TX)
```

## How afterCompletion() Knows This

```java
public void afterCompletion(int status) {
    Integer txId = (Integer) TransactionSynchronizationManager.getResource(TX_ID_KEY);
    
    if (txId != null) {
        if (status == STATUS_COMMITTED) {
            divergenceRecorder.resolveCommitted(txId);    // DELETE PENDING rows
        } else {
            divergenceRecorder.resolveRolledBack(txId, ...); // UPDATE to REJECTED_DATA
        }
    }
}
```

**status == STATUS_ROLLED_BACK** means "the business TX rolled back"

But the divergence rows are already committed and visible, so:

```sql
-- This UPDATE runs AFTER the rollback, in its own REQUIRES_NEW TX
UPDATE STL_PEP_DIVERGENCE
SET status = 'REJECTED_DATA', error = '{"reason": "LOCAL_TRANSACTION_NOT_COMMITTED", ...}'
WHERE transaction_id = 41
AND status = 'PENDING'

-- Result: all 3 rows flipped to REJECTED_DATA
-- They become visible to the SRE for manual cleanup
```

## Connection Pool Implications

This approach temporarily uses **TWO connections** from the pool during each `recordPending()` call:

```
Moment of recordPending() execution:

Pool has 10 connections (example):

Before:  [1:business] [2] [3] [4] [5] [6] [7] [8] [9] [10]
During:  [1:business(suspended)] [2:divergence] [3] [4] [5] [6] [7] [8] [9] [10]
After:   [1:business(resumed)] [2] [3] [4] [5] [6] [7] [8] [9] [10]
```

For the POC with 3 entities and 3 divergence calls, pool must accommodate:
- 1 connection for business TX (suspended but held) 
- +1 temporary connection for each divergence TX (released immediately after commit)

**In practice:** Peak usage is 2 connections for <1ms each. With a pool of 10, no contention.

**Under heavy load (100 concurrent requests):** Could reach 100 business TX connections + up to 100 temporary divergence connections. Tune pool size accordingly.
