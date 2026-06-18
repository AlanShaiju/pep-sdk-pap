# Connection Pool Risk: No Dedicated Pool for Divergence Records

## The Problem

**There is NO separate connection pool for divergence records.** Both the business transaction and divergence transactions draw from the same HikariCP pool.

### Default Configuration (POC)
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/pep_demo
    username: pep
    password: pep
    # No hikari config specified
```

**Defaults:**
```
HikariCP default settings:
- Maximum pool size: 10 connections
- Minimum idle: 10 connections  
- Connection timeout: 30 seconds
- Idle timeout: 10 minutes
```

### Pool State During a Single Request

```
Pool size: 10 connections

Normal case (3 entities):
  ├─ Business TX holds: 1 connection (SUSPENDED during divergence writes)
  ├─ Divergence TX #1:   1 connection (borrowed, committed immediately)
  ├─ Divergence TX #2:   1 connection (borrowed, committed immediately)
  ├─ Divergence TX #3:   1 connection (borrowed, committed immediately)
  └─ Available:          6 connections
  
  Peak usage: 2 connections at any moment
  Risk level: LOW for this POC
```

### Under Heavy Concurrent Load

```
Scenario: 8 concurrent requests, 3 entities each

Pool state:
  ├─ Request 1-8: Each holds 1 business connection (8 total)
  ├─ Request 1: Needs 1 connection for divergence TX #1
  │   └─ Available pool: 10 - 8 - 1 = 1 connection left
  ├─ Request 2: Needs 1 connection for divergence TX #1
  │   └─ Available: 0 connections
  │   └─ Action: WAITS (30 second default timeout)
  ├─ Request 3-8: Also waiting...
  │
  └─ After 30 seconds: TIMEOUT
      DataSourceException: Could not get a connection within 30 seconds
      
Consequence:
  ✗ Request 2-8 fail with connection timeout
  ✗ PAP calls already succeeded (entity data is on PAP)
  ✗ Divergence rows NEVER written
  ✗ Data in PAP without local record (UNDETECTED DIVERGENCE)
```

## Failure Scenarios

### 1. Connection Pool Exhaustion During recordPending()

```
Timeline:
  T1: 9 requests all in beforeCommit() phase
      └─ 9 connections held (suspended) by business TXs
      └─ 1 connection remains in pool
  
  T2: Request 1 calls recordPending() for entity #1
      └─ Gets the 1 remaining connection ✓
      └─ INSERT succeeds, COMMIT
      
  T3: Request 1 calls recordPending() for entity #2
      └─ Tries to get a connection
      └─ Pool exhausted: 0 available, 9 suspended + 0 free
      └─ Waits up to 30 seconds (default timeout)
      
  T4: After 30 seconds
      └─ DataSourceException: Could not get a connection
      └─ DivergenceRecorder throws PapSdkException
      └─ Exception propagates up through dispatch()
      └─ dispatch() throws, beforeCommit() exits
      └─ Business TX rolls back
      
Outcome:
  ✗ Entity #1: PENDING row written ✓
  ✗ Entity #2: PAP call succeeded ✓, but no PENDING row ✗ (UNDETECTED)
  ✗ Entity #3: never dispatched
  ✓ Business TX: rolled back
```

### 2. Network Failure to Postgres During divergenceRecorder.recordPending()

```
Scenario: Postgres becomes unreachable mid-request

Timeline:
  T3: recordPending() has opened a divergence TX
      └─ Gets connection from pool ✓
      
  T4: INSERT INTO STL_PEP_DIVERGENCE ...
      └─ Network packet sent to Postgres
      └─ Postgres network interface goes down
      └─ Socket timeout after 5 seconds
      
  T5: PostgreSQL JDBC throws CommunicationException
      └─ recordPending() catches as Exception
      └─ Wraps in PapSdkException("Failed to record divergence row...")
      └─ Throws back to dispatch()
      
  T6: dispatch() catches PapSdkException
      └─ Exception propagates to beforeCommit()
      └─ beforeCommit() exits with exception
      └─ Spring catches, triggers ROLLBACK
      
Outcome:
  ✓ PAP call succeeded (entity on PAP)
  ✗ Divergence INSERT failed (connection lost)
  ✗ No divergence row (UNDETECTED DIVERGENCE)
  ✓ Business TX rolled back
```

### 3. Postgres Out of Connections (Server-side)

```
Postgres default: max_connections = 100

If 100 other services/processes consume all 100 Postgres connections,
and this service tries to open a divergence TX connection:

Timeline:
  T3: divergenceRecorder.recordPending() needs a connection
      └─ HikariCP tries: dataSource.getConnection()
      └─ Postgres responds: 53000 FATAL too_many_connections
      
  T5: JDBC throws SQLException("FATAL: too_many_connections")
      └─ recordPending() catches as Exception
      └─ Wraps and throws PapSdkException
      └─ beforeCommit() exits
      └─ Business TX rolls back
      
Outcome:
  ✓ PAP call succeeded
  ✗ No divergence row
  ✗ UNDETECTED DIVERGENCE
```

## Why This Is a Real Vulnerability

The design assumes **Postgres is always reachable during beforeCommit()**. But:

1. **Network transience:** A 100ms blip to Postgres during divergence write loses the row forever
2. **Connection pool saturation:** High concurrency under peak load triggers timeouts
3. **Cascading failure:** If divergence writes fail, more entities get undetected divergences

The exact moment of vulnerability is narrow but critical:

```
Safe window (PAP call):
  - PAP is a separate service with its own fate
  - If PAP is down, the entity is never sent (fail-fast)
  - No divergence row needed (nothing sent)
  
UNSAFE window (divergence write):
  - PAP call already succeeded
  - Local DB hasn't committed yet
  - If divergence write fails now, there's no trace
```

## How It Currently Fails

When `recordPending()` throws an exception:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void recordPending(int txId, String entityType, String operation, Map<String, Object> payload) {
    try {
        String json = objectMapper.writeValueAsString(payload);
        repository.save(new PapDivergenceEntry(...));
    } catch (Exception e) {
        throw new PapSdkException("Failed to record divergence row for " + entityType, e);
        // ↑ This exception propagates up through dispatch()
    }
}
```

From `PapTransactionSynchronization.dispatch()`:

```java
int txId = currentOrNewTransactionId();
divergenceRecorder.recordPending(txId, ...);  // ← If this throws...
// ... exception propagates, dispatch() doesn't catch it
// ... beforeCommit() exits with exception
// ... Spring's TM catches, triggers ROLLBACK
```

The business transaction rolls back (good), but **the PAP call already succeeded** (bad).

## Current Mitigation: Fail-Fast

The only mitigation is **fail-fast on the first error**:

- If divergence write fails on entity #1, entities #2 and #3 are never sent to PAP
- At least the divergence is "consistent" across all entities (either all undetected or none)
- But that's still a divergence

## Better Solutions (Not in Current POC)

### Option 1: Dedicated Connection Pool for Divergence

```java
@Configuration
public class DataSourceConfig {
    
    @Bean("mainDataSource")
    public DataSource mainDataSource() {
        return DataSourceBuilder.create()
            .url("jdbc:postgresql://localhost:5432/pep_demo")
            .username("pep")
            .password("pep")
            .hikari(new HikariConfig() {
                {
                    setMaximumPoolSize(10);  // Business TX pool
                    setMinimumIdle(5);
                }
            })
            .build();
    }
    
    @Bean("divergenceDataSource")
    public DataSource divergenceDataSource() {
        return DataSourceBuilder.create()
            .url("jdbc:postgresql://localhost:5432/pep_demo")
            .username("pep")
            .password("pep")
            .hikari(new HikariConfig() {
                {
                    setMaximumPoolSize(5);  // Divergence TX pool (separate!)
                    setMinimumIdle(2);
                    setConnectionTimeout(5000);  // Fail fast
                }
            })
            .build();
    }
    
    @Bean
    public JpaTransactionManager transactionManager(@Qualifier("mainDataSource") DataSource ds) {
        return new JpaTransactionManager(entityManagerFactory(ds).getObject());
    }
}
```

Then route divergence operations to `@Qualifier("divergenceDataSource")`:

```java
@Repository
public interface PapDivergenceRepository extends JpaRepository<PapDivergenceEntry, Integer> {
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Qualifier("divergenceDataSource")  // Routes to separate pool
    void recordPending(...);
}
```

**Trade-off:** More complex configuration, but guarantees divergence writes never starve waiting for business TX connections.

### Option 2: Async Divergence Recording

Record divergence asynchronously (after commit) instead of inside beforeCommit():

```java
public void afterCompletion(int status) {
    if (status == STATUS_COMMITTED) {
        // Don't delete yet; write an async task
        divergenceQueue.enqueue(txId);
    }
}

@Async
public void recordDivergenceAsync(int txId) {
    // Retry logic here
    // Independent lifecycle from business TX
}
```

**Trade-off:** Slightly more complex, but eliminates the REQUIRES_NEW suspend/resume overhead and the connection pool contention entirely.

### Option 3: Batch Divergence Writes

Instead of one INSERT per entity in beforeCommit(), batch all three INSERTs into one round-trip:

```java
public void beforeCommit() {
    // ... dispatch all entities ...
    if (txId != null) {
        // One connection, one round-trip
        divergenceRecorder.recordPendingBatch(txId, allPendingEntities);
    }
}
```

**Trade-off:** Simpler, lower connection overhead, but requires buffering the entities through dispatch.

## Recommended Production Configuration

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/pep_demo
    username: pep
    password: pep
    hikari:
      maximum-pool-size: 15           # Business TXs
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      auto-commit: false
      transaction-isolation: READ_COMMITTED
```

For heavy concurrency (100+ concurrent requests), consider Option 1 (separate divergence pool) to guarantee no starving divergence writes.

## Testing the Failure

To reproduce connection pool exhaustion:

```java
@Test
public void testDivergenceWriteUnderPoolExhaustion() {
    DataSource ds = DataSourceBuilder.create()
        .hikari(new HikariConfig() { setMaximumPoolSize(2); })  // Tiny pool
        .build();
    
    // Start 10 concurrent requests
    // Each holds 1 connection in beforeCommit()
    // 10 > 2, so 8 will timeout trying divergence writes
    
    // Assert: PAP calls succeeded but divergence rows are missing
}
```

## Summary: Is It Handled?

**No.** There is no dedicated pool for divergence records in the current POC.

**Risk Level:** MEDIUM
- Low concurrency (< 5 simultaneous requests): Risk is minimal
- Medium concurrency (5-10): Possible but unlikely
- High concurrency (10+): High probability of divergence write failures

**Current Mitigation:** None. Divergence write failures propagate and roll back the business TX, but the PAP call already succeeded (undetected divergence).

**Recommended Fix:** Either Option 1 (separate pool) or Option 2 (async divergence) for production deployments with expected concurrency > 5 requests/sec.
