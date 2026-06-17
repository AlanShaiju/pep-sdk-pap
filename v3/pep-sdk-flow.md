# PEP SDK — Complete Flow: Compile → Startup → Request (SYNC Path)

## COMPILE TIME

### Phase: Annotation Processor Validation & Metadata Generation

**Entry Point:** `javac` with `-processorpath sdk-processor.jar` discovers processor via `META-INF/services/javax.annotation.processing.Processor`

---

#### 1. **PapAnnotationProcessor** (sdk-processor)
   - **Action:** Processes every `@PapEntity`-annotated class
   - **Triggered by:** `javac` scanning classpath for annotated elements
   - **How:**
     1. Calls `init(ProcessingEnvironment)` once per module (inherited from `AbstractProcessor`)
     2. Implements `process(Set<? extends TypeElement>, RoundEnvironment)` 
     3. For each element in `RoundEnvironment.getElementsAnnotatedWith(PapEntity.class)`:

#### 2. **For each @PapEntity class: validate, then generate metadata**

   a) **Load compile-time catalog:**
      - Class: `CompileTimeCatalog`
      - Action: Parse `pap-endpoints.json` bundled in sdk-processor resources
      - How: Hand-rolled JSON parser (no Jackson), reads `entity` field names into a `Map<String, EntityInfo>`
      - **Failure scenario 1:** If `pap-endpoints.json` is malformed → `PapSdkException` during parsing

   b) **Validate entity exists in PAP catalog:**
      - Class: `PapAnnotationProcessor.process()`
      - Action: Check `@PapEntity.entity` value against `CompileTimeCatalog.supports(entityName)`
      - **Failure scenario 2:** Entity name not in catalog → error message, compilation fails
      ```
      @PapEntity(entity = "ResourceInstance")  // checked against catalog
      public class Pipeline { }
      ```

   c) **Validate @PapAttribute fields:**
      - Class: `PapAnnotationProcessor.process()`
      - Action: 
         - Collect every field with `@PapAttribute` 
         - Confirm `@Id` exists (required for entity identity)
         - Check no duplicate source names across attributes/properties/includes
      - **Failure scenario 3:** Missing `@Id` → compilation error
      - **Failure scenario 4:** Duplicate source name → warning or error

   d) **Validate @PapInclude dot-paths:**
      - Class: `DotPathValidator`
      - Action: For each `@PapInclude(attribute = "region.code")`, walk the field chain via reflection
      - How: 
         ```
         String path = "region.code";
         // Split into ["region", "code"], resolve each against the field's type
         ```
      - **Failure scenario 5:** Dot-path doesn't resolve (e.g., "region" field doesn't exist) → compilation error

   e) **Check @PapInclude on collections:**
      - Class: `PapAnnotationProcessor.process()`
      - Action: Warn if `@PapInclude` is on a `Collection` field (out of scope — only single-valued relationships)
      - **Failure scenario 6:** Collection detected → warning (non-fatal)

   f) **Check coverage rule:**
      - Class: `PapAnnotationProcessor.process()`
      - Action: For every source name the catalog requires, confirm it's reachable via some `@PapAttribute` / `@PapProperty` / `@PapInclude`
      - **Failure scenario 7:** Required source not reachable → warning or error

   g) **Generate metadata.json:**
      - Class: `PapAnnotationProcessor.process()`
      - Action: For each validated entity, emit `metadata.json` to `target/classes/META-INF/pep-sdk/`
      - How: Calls `processingEnv.getFiler().createResource()`
      - Content structure:
        ```json
        {
          "entityClass": "com.example.pep.demo.Pipeline",
          "papEntity": "ResourceInstance",
          "attributes": [
            { "fieldName": "id", "attributeName": "id" },
            { "fieldName": "code", "attributeName": "code" }
          ],
          "includes": [
            { "fieldName": "tenant", "name": "tenant_id", "attribute": "id" }
          ],
          "properties": {
            "resource_type_id": "101"
          },
          "operationModes": { "CREATE": "SYNC", "UPDATE": "SYNC", "DELETE": "SYNC" }
        }
        ```
      - **Failure scenario 8:** File creation fails → `PapSdkException`, compilation aborts

---

**Compile-time summary:** If all validations pass, each module has its own `metadata.json` in its compiled output JAR. If any validation fails, `javac` reports errors and compilation stops.

---

## STARTUP TIME

### Phase: SDK Initialization (Spring Boot AutoConfiguration)

**Entry Point:** Spring discovers `PapSdkAutoConfiguration` via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

---

#### 1. **PapSdkAutoConfiguration** (sdk-spring-boot-starter) — creates all SDK beans in dependency order

   a) **Load metadata and build registry:**
      - Class: `MetadataLoader.load(ClassLoader)`
      - Action: Discover all `metadata.json` files on classpath; build entity registry
      - How:
        ```java
        classLoader.getResources("META-INF/pep-sdk/metadata.json")
          // Returns every matching resource across all modules
        // For each: parse JSON, resolve dot-paths via reflection
        ```
      - Dependencies: `metadata.json` files from compile time (must exist)
      - Creates: `PapEntityRegistry` bean
      - **Failure scenario 1:** No `metadata.json` found → empty registry (may be OK if no entities)
      - **Failure scenario 2:** Dot-path resolution fails at runtime (despite passing compile-time check) → `PapSdkException`, app fails to start
      - **Failure scenario 3:** Duplicate entity class across modules (two modules declare same class) → `IllegalArgumentException`, app fails

   b) **Load PAP catalog:**
      - Class: `PapCatalog` constructor
      - Action: Parse `pap-endpoints.json` from `sdk-core` resources
      - How: Jackson's `ObjectMapper.readTree()` parses endpoint specs
      - Creates: `PapCatalog` bean
      - **Failure scenario 4:** Catalog file malformed or missing → `PapSdkException`, app fails

   c) **Create request builder:**
      - Class: `PapRequestBuilder` constructor
      - Action: Takes `PapCatalog` + `EndpointResolver`, prepares to build HTTP requests
      - Dependencies: `PapCatalog`, `EndpointResolver`
      - Creates: `PapRequestBuilder` bean

   d) **Create REST client with resilience:**
      - Class: `PapSdkAutoConfiguration.papSdkRestClient()`
      - Action: Build `RestClient` with timeouts, retry, circuit breaker
      - How:
        ```java
        RestClient.builder()
          .baseUrl(props.getBaseUrl())  // from application.yml: http://localhost:8080
          .requestFactory(simpleFactory)
          .build()
        ```
      - Dependencies: `pap.sdk.base-url` property (must be set)
      - Creates: `RestClient` bean
      - **Failure scenario 5:** `base-url` is null/blank → `PapSdkException`, app fails

   e) **Create PAP client:**
      - Class: `PapClient` constructor
      - Action: Wraps `RestClient`, adds retry + circuit breaker logic
      - How:
        ```java
        new PapClient(restClient, retry, circuitBreaker)
        ```
      - Dependencies: `RestClient`, `Retry` policy, `CircuitBreaker`
      - Creates: `PapClient` bean

   f) **Create shared EntityManager proxy:**
      - Class: `PapSdkAutoConfiguration.papSdkSharedEntityManager()`
      - Action: Create transaction-aware `EntityManager` proxy (same as `@PersistenceContext` injection produces)
      - How:
        ```java
        SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory)
        ```
      - Dependencies: `EntityManagerFactory` (from Spring Data JPA)
      - Creates: `EntityManager` bean (singleton, routes to active transaction's EM)

   g) **Create divergence recorder:**
      - Class: `DivergenceRecorder` constructor
      - Action: Set up database sequence access and divergence row persistence
      - How:
        ```java
        new DivergenceRecorder(
          repository,           // Spring Data JPA repo for STL_PEP_DIVERGENCE
          entityManager,        // shared proxy from step (f)
          objectMapper,         // Jackson for JSON serialization
          clock                 // system clock for timestamps
        )
        ```
      - Dependencies: `PapDivergenceRepository`, `EntityManager`, `ObjectMapper`
      - Creates: `DivergenceRecorder` bean
      - **Failure scenario 6:** `STL_PEP_DIVERGENCE` table doesn't exist → `DataAccessException` when first divergence write is attempted (happens at runtime, not startup)

   h) **Create transaction synchronization:**
      - Class: `PapTransactionSynchronization` constructor
      - Action: Set up the before/after commit hooks that orchestrate dispatch
      - How:
        ```java
        new PapTransactionSynchronization(
          registry,                    // from step (a)
          requestBuilder,              // from step (c)
          papClient,                   // from step (e)
          divergenceRecorder,          // from step (g)
          entityManager                // from step (f)
        )
        ```
      - Dependencies: All of the above
      - Creates: `PapTransactionSynchronization` bean (singleton)

   i) **Create entity listener:**
      - Class: `PapEntityListener` constructor
      - Action: Set up Hibernate event capture
      - How:
        ```java
        new PapEntityListener(registry, papTxnSync)
        ```
      - Dependencies: `PapEntityRegistry`, `PapTransactionSynchronization`
      - Creates: `PapEntityListener` bean

   j) **Register listener with Hibernate:**
      - Class: `HibernateListenerRegistrar.register()` (called during bean construction)
      - Action: Unwrap `EntityManagerFactory` to Hibernate's `SessionFactory`, register listener for POST_INSERT/UPDATE/DELETE
      - How:
        ```java
        sessionFactory.getEventListenerRegistry()
          .appendListeners(EventType.POST_INSERT, listener)
          .appendListeners(EventType.POST_UPDATE, listener)
          .appendListeners(EventType.POST_DELETE, listener)
        ```
      - Dependencies: `EntityManagerFactory`, `PapEntityListener`
      - **Failure scenario 7:** `EntityManagerFactory` is not Hibernate-backed → `ClassCastException`, app fails

   k) **Verify schema (via Flyway):**
      - Class: Flyway migrations (external, not SDK code)
      - Action: Create `STL_PEP_DIVERGENCE` table and `pap_divergence_transaction_seq` sequence (if not existing)
      - How: Runs `V2__pep_divergence.sql` on app startup
      - **Failure scenario 8:** Postgres not running or connection denied → Flyway fails, app fails

**Startup-time summary:** All beans are created, Hibernate listener is registered, schema is ready. App is now ready to accept requests.

---

## REQUEST HANDLING TIME (SYNC Path)

### Scenario: HTTP POST `/resources/ordered` creates three entities in one @Transactional method

---

#### 1. **HTTP request arrives at ResourceController**

   - Class: `ResourceController.ordered()`
   - Method marked: `@Transactional` (Spring opens a transaction)
   - Action: Calls three repository saves in sequence
   ```java
   @Transactional
   public ResponseEntity<String> ordered(...) {
       pipelines.save(new Pipeline(...));    // Entity A
       deployments.save(new Deployment(...)); // Entity B
       monitors.save(new Monitor(...));       // Entity C
       return ResponseEntity.ok(...);
   }
   ```
   - Transaction is opened, still active after method returns (Spring commits just before response is sent)

---

#### 2. **First entity saved: Pipeline**

   - Class: `PipelineRepository.save()` → Hibernate `Session.persist()`
   - Action: Hibernate queues INSERT for Pipeline
   - For `IDENTITY` strategy: SQL executes immediately, `POST_INSERT` event fires
   - **Note:** This is **inside the open transaction**, which is still **rollback-able**

---

#### 3. **Hibernate POST_INSERT event fires**

   - Listener: `PapEntityListener.onPostInsert(PostInsertEvent)`
   - Action: 
     1. Get entity class from event: `Pipeline.class`
     2. Check transaction is active: `TransactionSynchronizationManager.isSynchronizationActive()`
     3. Get or create `ChangeBuffer` for this transaction:
        ```java
        ChangeBuffer buffer = (ChangeBuffer) TransactionSynchronizationManager.getResource(BUFFER_KEY);
        if (buffer == null) {
            buffer = new ChangeBuffer();
            TransactionSynchronizationManager.bindResource(BUFFER_KEY, buffer);
            TransactionSynchronizationManager.registerSynchronization(papTxnSync);
            // ↑ Registers PapTransactionSynchronization callbacks
        }
        ```
     4. Create `PapEntityChange`:
        ```java
        new PapEntityChange(
            Pipeline.class,           // entity class
            Operation.CREATE,         // from hibernate event type
            CommunicationMode.SYNC,   // from @PapOperationMode
            id,                       // extracted from entity
            entity                    // the entity itself
        )
        ```
     5. Append to buffer:
        ```java
        buffer.append(change)  // LinkedHashMap — preserves insertion order
        ```
   - **Failure scenario 1:** No active transaction → `PapSdkException`, controller gets exception
   - **Failure scenario 2:** Entity class not in registry → `PapSdkException`, transaction rolls back

---

#### 4. **Deployment saved (Entity B)**

   - Same as step 2–3: `POST_UPDATE`/`POST_DELETE` events fire, `PapEntityChange` appended to the *same* `ChangeBuffer`
   - Buffer now contains: `[Pipeline (CREATE), Deployment (CREATE)]`
   - Still inside transaction, still rollback-able

---

#### 5. **Monitor saved (Entity C)**

   - Same process: Monitor `POST_INSERT` fires, appended
   - Buffer now contains: `[Pipeline, Deployment, Monitor]` — **insertion order preserved**
   - Still inside transaction

---

#### 6. **Controller method returns**

   - Transaction is **still open**, but about to commit
   - Spring's `TransactionManager.commit()` is invoked
   - Before actual JDBC commit, it calls `TransactionSynchronization.beforeCommit(boolean readOnly)`

---

#### 7. **PapTransactionSynchronization.beforeCommit()** — THE CRITICAL PHASE

   - Class: `PapTransactionSynchronization`
   - Action: Dispatch every buffered change to PAP, in order
   - How:
     ```java
     // Step 1: Flush any deferred SQL
     entityManager.flush()  
     // Executes any deferred UPDATE/DELETE SQL that hasn't run yet
     // Fires any remaining POST_UPDATE/POST_DELETE events
     
     // Step 2: Get the buffer
     ChangeBuffer buffer = (ChangeBuffer) TransactionSynchronizationManager.getResource(BUFFER_KEY);
     if (buffer == null || buffer.isEmpty()) return;
     
     // Step 3: Drain buffer (removing all entries, returning them in order)
     for (PapEntityChange change : buffer.drain()) {
         dispatch(change);  // See step 8 below
     }
     ```
   - **Failure scenario 1:** `entityManager.flush()` fails (e.g., constraint violation) → exception thrown, `beforeCommit()` exits early, transaction rolls back, no PAP call made for this entity or later ones

---

#### 8. **dispatch(PapEntityChange change)** — For each entity, one at a time

   **For Pipeline (Entity A):**

   a) **Get descriptor from registry:**
      ```java
      PapEntityDescriptor descriptor = registry.find(Pipeline.class).orElseThrow(...)
      // Descriptor contains: papEntity="ResourceInstance", 
      //                       properties={"resource_type_id": "101"},
      //                       attributes=[{fieldName: "id", attributeName: "id"}, ...],
      //                       includes=[{fieldName: "tenant", name: "tenant_id", ...}]
      ```
      - **Failure scenario 1:** Descriptor not found → `PapSdkException`, rolls back

   b) **Check mode:**
      ```java
      if (change.mode() != CommunicationMode.SYNC) throw new PapSdkException(...)
      ```
      - **Failure scenario 2:** Mode is ASYNC (shouldn't happen in this POC) → throws, rolls back

   c) **Build the request:**
      ```java
      PapRequest req = requestBuilder.build(descriptor, change)
      ```
      - Class: `PapRequestBuilder.build()`
      - How:
        1. Call `descriptor.resolveSources(entity)` — walks @PapAttribute/@PapProperty/@PapInclude precedence, builds source map
           ```java
           Map<String, Object> sources = descriptor.resolveSources(entity);
           // sources = {
           //   "id": 1,
           //   "code": "PIPE-1",
           //   "name": "pipeline one",
           //   "description": "first",
           //   "resource_type_id": "101",          // from @PapProperty
           //   "propagate": "true",                // from @PapProperty
           //   "tenant_id": 7                      // from @PapInclude walking tenant.id
           // }
           ```
        2. Look up endpoint from catalog:
           ```java
           EndpointSpec endpoint = catalog.endpointFor("ResourceInstance")
           // endpoint = {
           //   path: "/pap/v1/resource-instances",
           //   pathVariables: {},
           //   headers: {"tenant_id": "header"},
           //   queryParams: {},
           //   body: ["id", "code", "name", "description", "resource_type_id", "propagate"]
           // }
           ```
        3. Build HTTP request:
           ```java
           HttpRequest req = new HttpRequest(
               method: HttpMethod.POST,
               path: "/pap/v1/resource-instances",
               headers: {"tenant_id": "7"},
               body: {
                   "id": 1,
                   "code": "PIPE-1",
                   "name": "pipeline one",
                   "description": "first",
                   "resource_type_id": "101",
                   "propagate": "true"
               }
           )
           ```
      - **Failure scenario 3:** Entity not in catalog → `PapSdkException`, rolls back
      - **Failure scenario 4:** Dot-path resolution fails (e.g., tenant is null) → `PapSdkException`, rolls back

   d) **Send to PAP (with retry + circuit breaker):**
      ```java
      papClient.send(req)  // Blocking call
      ```
      - Class: `PapClient.send()`
      - How:
        1. Wrap with `Retry.executeSupplier()`:
           ```java
           Retry.executeSupplier(() -> 
               circuitBreaker.executeSupplier(() -> 
                   doSend(req)
               )
           )
           ```
        2. Inside `doSend()`:
           ```java
           // Build full URL
           url = baseUrl + path = "http://localhost:8080/pap/v1/resource-instances"
           
           // Set headers
           headers.set("tenant_id", "7")
           
           // Make REST call
           ResponseEntity<Void> resp = restClient.post()
               .uri(url)
               .headers(h -> headers.forEach(h::set))
               .body(body)
               .retrieve()
               .toBodilessEntity()
           
           // Check response
           if (!resp.getStatusCode().is2xxSuccessful()) {
               if (resp.getStatusCode().is4xxClientError()) {
                   throw new PapRejectedException(statusCode, reason)
               } else {
                   throw new PapUnavailableException(...)
               }
           }
           ```
      - **Failure scenario 5 (4xx from PAP — e.g., validation error):**
        - `PapClient.send()` throws `PapRejectedException`
        - Exception propagates up through `dispatch()` back to `beforeCommit()`
        - **No divergence row is written** — nothing succeeded
        - Transaction rolls back (spring catches exception, triggers rollback)
        - Entity B and C are never sent

      - **Failure scenario 6 (5xx from PAP):**
        - `PapClient.send()` throws `PapUnavailableException`
        - Retry policy (default: 3 attempts) kicks in automatically
        - If all retries fail: exception propagates, same outcome as scenario 5
        - If a retry succeeds: continue to step (e) below

      - **Failure scenario 7 (network timeout, connection refused):**
        - `RestClient` throws `ResourceAccessException`
        - Retry policy kicks in, eventually gives up
        - Exception propagates, same outcome as scenario 5

      - **Failure scenario 8 (circuit breaker open):**
        - If previous requests to PAP failed frequently, circuit breaker trips
        - `CircuitBreaker.executeSupplier()` throws `CallNotPermittedException` immediately (fail-fast)
        - No HTTP call is made, exception propagates like scenario 5

   e) **PAP accepted the request (2xx response):**
      ```java
      // Success! Now generate transaction_id and write divergence row
      int txId = currentOrNewTransactionId()
      // Inside currentOrNewTransactionId():
      //   Integer existing = getResource(TX_ID_KEY);
      //   if (existing != null) return existing;
      //   int txId = divergenceRecorder.nextTransactionId();  // ← DB sequence fetch
      //   bindResource(TX_ID_KEY, txId);
      //   return txId;
      
      divergenceRecorder.recordPending(txId, "ResourceInstance", "CREATE", fullRequestShape(req))
      ```
      - Class: `DivergenceRecorder.recordPending()`
      - How:
        ```java
        @Transactional(propagation = REQUIRES_NEW)
        public void recordPending(int txId, String entityType, String operation, Map<String, Object> payload) {
            String json = objectMapper.writeValueAsString(payload);
            repository.save(new PapDivergenceEntry(txId, entityType, operation, json, clock.instant()));
        }
        ```
        - **REQUIRES_NEW:** Suspends the business transaction (Pipeline/Deployment/Monitor), opens a separate transaction, executes INSERT into `STL_PEP_DIVERGENCE`, commits *immediately*, then resumes the business transaction
        - Row inserted:
          ```sql
          INSERT INTO STL_PEP_DIVERGENCE (transaction_id, entity_type, operation, payload, created_at, status)
          VALUES (41, 'ResourceInstance', 'CREATE', '{...full request...}', now(), 'PENDING')
          ```
        - Row is now durable, visible to other transactions
        - **Failure scenario 9 (Postgres connection lost during INSERT):**
          - `DivergenceRecorder.recordPending()` throws `DataAccessException`
          - Exception propagates back to `dispatch()`
          - Business transaction rolls back (PAP write succeeded but divergence row was lost)
          - **Data inconsistency:** PAP has the record, local DB doesn't, and nothing is flagged in STL_PEP_DIVERGENCE
          - This is a real, unrecoverable gap (noted in design doc §12.1 as "residual window")

   **For Deployment (Entity B) and Monitor (Entity C):**
   - Same process: `dispatch()` → build request → send to PAP → `currentOrNewTransactionId()` returns the same `txId` (41) — no new sequence fetch → `recordPending(41, ...)`
   - Buffer now contains only these three, all with the same `transaction_id` (41)

---

#### 9. **beforeCommit() completes successfully**

   - All three entities dispatched to PAP (all succeeded)
   - Three PENDING rows written to `STL_PEP_DIVERGENCE` with `transaction_id=41`
   - Control returns to Spring's `TransactionManager.commit()`

---

#### 10. **Spring issues JDBC commit**

   - Class: `JpaTransactionManager.doCommit()`
   - Action: Commits the business transaction
   - SQL executed: `INSERT` for Pipeline, Deployment, Monitor all committed atomically
   - **Failure scenario 1 (constraint violation during commit):**
     - Example: A unique constraint on `Pipeline.code` is violated by two rows with same code
     - JDBC commit fails with `SQLException`
     - Spring's `JpaTransactionManager` catches this, triggers rollback
     - Control goes to `afterCompletion(STATUS_ROLLED_BACK)`

   - **Normal case:** All three entities committed successfully to local DB

---

#### 11. **afterCompletion(int status)** — Resolution phase

   - Class: `PapTransactionSynchronization.afterCompletion()`
   - How:
     ```java
     Integer txId = (Integer) TransactionSynchronizationManager.getResource(TX_ID_KEY);
     if (txId != null) {
         if (status == STATUS_COMMITTED) {
             divergenceRecorder.resolveCommitted(txId);  // ← DELETE
             // Query:
             // DELETE FROM STL_PEP_DIVERGENCE WHERE transaction_id = 41 AND status = 'PENDING'
             // Deletes all three rows. No divergence actually occurred.
         } else {
             divergenceRecorder.resolveRolledBack(txId, "LOCAL_TRANSACTION_NOT_COMMITTED", "...");
             // Query:
             // UPDATE STL_PEP_DIVERGENCE SET status = 'REJECTED_DATA', error = '{"reason": ...}'
             //   WHERE transaction_id = 41 AND status = 'PENDING'
             // Updates all three rows. They're now flagged for an SRE to investigate.
         }
         TransactionSynchronizationManager.unbindResource(TX_ID_KEY);
     }
     if (TransactionSynchronizationManager.hasResource(BUFFER_KEY)) {
         TransactionSynchronizationManager.unbindResource(BUFFER_KEY);
     }
     ```

   **Case 1: Local transaction committed successfully (status == COMMITTED)**
   - `resolveCommitted(41)` executes, deletes all three PENDING rows
   - Result: No trace in `STL_PEP_DIVERGENCE`
   - **Failure scenario:** DELETE fails → `DataAccessException`, but transaction is already committed — inconsistency (rare, covered in design doc §12.1)

   **Case 2: Local transaction rolled back (status == ROLLED_BACK)**
   - `resolveRolledBack(41, ...)` executes
     ```java
     @Transactional(propagation = REQUIRES_NEW)
     public void resolveRolledBack(int txId, String errorReason, String errorMessage) {
         String errorJson = objectMapper.writeValueAsString(
             Map.of("reason", errorReason, "message", errorMessage)
         );
         repository.markRejectedByTransactionId(txId, errorJson);
     }
     ```
   - Updates all three rows to `REJECTED_DATA`:
     ```sql
     UPDATE STL_PEP_DIVERGENCE
     SET status = 'REJECTED_DATA', error = '{"reason": "LOCAL_TRANSACTION_NOT_COMMITTED", ...}'
     WHERE transaction_id = 41 AND status = 'PENDING'
     ```
   - Result: All three rows now visible in divergence table with status REJECTED_DATA
   - SRE reviews these rows and manually deletes corresponding records from PAP
   - **Failure scenario:** UPDATE fails → exception, but business transaction is already rolled back — rows stay PENDING, will need manual cleanup

---

#### 12. **Response sent to client**

   - Controller's return value serialized and sent back to HTTP client
   - If everything succeeded: `200 OK: "created Pipeline -> Deployment -> Monitor ..."`
   - If any exception occurred before `beforeCommit()` returned: `500 Internal Server Error` (Spring rolls back, exception propagates)

---

## FAILURE SCENARIOS — SUMMARY TABLE

| Scenario | When | Where | Exception | Local TX | PAP State | Divergence Table |
|---|---|---|---|---|---|---|
| 1 | Listener capture | `PapEntityListener.onPostInsert()` | `PapSdkException` | Rolled back | Not called | Empty |
| 2 | Descriptor lookup | `dispatch()` | `PapSdkException` | Rolled back | Not called | Empty |
| 3 | Catalog lookup | `PapRequestBuilder.build()` | `PapSdkException` | Rolled back | Not called | Empty |
| 4 | Dot-path resolution | `descriptor.resolveSources()` | `PapSdkException` | Rolled back | Not called | Empty |
| 5 | PAP 4xx rejection | `PapClient.send()` | `PapRejectedException` | Rolled back | Rejected | Empty (no row for rejected entity, rows for earlier ones stay PENDING until timeout) |
| 6 | PAP 5xx, retries exhausted | `PapClient.send()` | `PapUnavailableException` | Rolled back | Failed | Empty |
| 7 | Network timeout | `PapClient.send()` | `ResourceAccessException` | Rolled back | Failed | Empty |
| 8 | Circuit breaker open | `PapClient.send()` | `CallNotPermittedException` | Rolled back | Not called | Empty |
| 9 | Divergence INSERT fails | `divergenceRecorder.recordPending()` | `DataAccessException` | Rolled back | Succeeded! | Missing (undetected divergence) |
| 10 | Local commit constraint violation | `JpaTransactionManager.doCommit()` | `PersistenceException` | Rolled back | Succeeded | PENDING → REJECTED_DATA |
| 11 | Divergence UPDATE fails | `divergenceRecorder.resolveRolledBack()` | `DataAccessException` | Already rolled back | Succeeded | Stays PENDING (manual cleanup needed) |

---

## Key Insights

1. **Dispatch is fail-fast:** The moment any entity's PAP call fails, the loop exits and the entire transaction rolls back. Entities B and C in this example are never sent.

2. **transaction_id is lazy:** It's only generated when the first PAP call *succeeds*. A transaction whose first entity is rejected never consumes a sequence value.

3. **PENDING rows are independent:** Even if the business transaction rolls back after a PENDING row is written, that row survives (durable in its own REQUIRES_NEW transaction). Resolution flips it to REJECTED_DATA.

4. **Unresolved windows:** Two scenarios leave divergence undetected:
   - PAP call succeeds, but divergence INSERT fails before returning (Failure #9) — row never written
   - Local commit fails but divergence UPDATE fails later (Failure #11) — row stays PENDING

5. **Order is preserved:** The buffer's LinkedHashMap ensures dispatch order matches entity creation order, despite Hibernate's potential reordering.

