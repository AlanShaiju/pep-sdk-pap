# PEP SDK Flow — Detailed Class Dependencies, Packages, Methods & ThreadLocal

## COMPILE TIME

### 1. PapAnnotationProcessor

**Package & Location:**
```
com.example.pep.sdk.processor.PapAnnotationProcessor
Module: sdk-processor
JAR: sdk-processor-0.1.0-SNAPSHOT.jar
```

**Extends:**
```
extends AbstractProcessor (javax.annotation.processing.AbstractProcessor)
```

**Key Methods:**
```
- init(ProcessingEnvironment processingEnv)
  └─ Called once per module compilation by javac
  
- process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv)
  └─ Called for each round of annotation processing
  └─ Returns: boolean (true = annotations claimed by this processor)
```

**Uses (Direct Dependencies):**
```
1. CompileTimeCatalog
   ├─ Package: com.example.pep.sdk.processor
   ├─ Purpose: Load and validate PAP endpoint catalog
   └─ Methods: supports(String entityName), get(String entityName)

2. DotPathValidator
   ├─ Package: com.example.pep.sdk.processor
   ├─ Purpose: Validate @PapInclude dot-paths
   └─ Methods: validate(String dotPath, TypeMirror fieldType)

3. javax.annotation.processing.ProcessingEnvironment (JDK)
   ├─ Purpose: Access to compiler and resource generation
   └─ Methods: getFiler(), getElementUtils(), getTypeUtils()

4. javax.annotation.processing.Filer (JDK)
   ├─ From: ProcessingEnvironment.getFiler()
   ├─ Purpose: Create metadata.json resource files
   └─ Methods: createResource(StandardLocation.SOURCE_OUTPUT, ...)

5. com.fasterxml.jackson.databind.ObjectMapper
   ├─ From: com.fasterxml.jackson:jackson-databind (external lib)
   ├─ Purpose: Serialize metadata to JSON
   └─ Methods: writeValueAsString(Object value)
```

**ThreadLocal Usage:**
```
NO - Annotation processor runs in single-threaded javac context
All state is local to process() method invocation
```

**Call Flow:**
```
javac discovers processor via META-INF/services/
  ↓
PapAnnotationProcessor.init(processingEnv)
  ├─ Save ProcessingEnvironment
  ├─ Initialize ElementUtils, TypeUtils
  └─ Instantiate CompileTimeCatalog(from sdk-processor/resources)
      └─ CompileTimeCatalog.__init__()
         └─ Load pap-endpoints.json from classpath resource
         └─ Parse JSON → Map<String, EntityInfo>
  
PapAnnotationProcessor.process(annotations, roundEnv)
  ├─ For each @PapEntity annotated class:
  │  ├─ Call CompileTimeCatalog.supports(entityName)
  │  ├─ Get element utilities: processingEnv.getElementUtils()
  │  ├─ Validate @Id field exists
  │  ├─ Validate @PapAttribute/@PapInclude fields
  │  ├─ For each @PapInclude: DotPathValidator.validate(dotPath, fieldType)
  │  └─ Generate metadata.json via Filer
  │     └─ processingEnv.getFiler().createResource(
  │        location=SOURCE_OUTPUT,
  │        pkg="META-INF/pep-sdk",
  │        relativeName="metadata.json"
  │     )
  │     └─ Write JSON via Jackson ObjectMapper
  └─ Return true (annotations handled)
```

---

### 2. CompileTimeCatalog

**Package & Location:**
```
com.example.pep.sdk.processor.CompileTimeCatalog
Module: sdk-processor
```

**Key Methods:**
```
- CompileTimeCatalog()
  ├─ Loads pap-endpoints.json from sdk-processor/src/main/resources
  └─ Parses manually (no Jackson) into Map<String, EntityInfo>

- boolean supports(String entityName)
  └─ Checks if entity exists in catalog

- EntityInfo get(String entityName)
  └─ Returns endpoint specification for entity
```

**Uses:**
```
1. Manual JSON parsing (no external libs to keep processor lightweight)
   ├─ Reads from: sdk-processor/src/main/resources/pap-endpoints.json
   └─ Parses via: String.split(), indexOf(), substring()

2. Class loading
   ├─ Purpose: Resolve field types for dot-path validation
   └─ Method: Class.forName(String className)
```

**Data Structure:**
```
Map<String, EntityInfo>
└─ Key: "ResourceInstance"
   └─ Value: EntityInfo {
      path: "/pap/v1/resource-instances",
      method: "POST",
      headers: {tenant_id: "header"},
      body: [field names],
      pathVariables: {},
      queryParams: {}
   }
```

---

### 3. DotPathValidator

**Package & Location:**
```
com.example.pep.sdk.processor.DotPathValidator
Module: sdk-processor
```

**Key Methods:**
```
- void validate(String dotPath, TypeMirror fieldType)
  ├─ Splits path: "region.code" → ["region", "code"]
  └─ Walks type chain via reflection
```

**Uses:**
```
1. javax.lang.model.type.TypeMirror (JDK)
   ├─ From: field annotations at compile time
   └─ Methods: asElement(), getQualifiedName()

2. javax.lang.model.element.Element (JDK)
   ├─ Purpose: Navigate declared fields
   └─ Methods: getEnclosedElements()

3. java.lang.reflect.Field (JDK)
   ├─ Purpose: Validate path at compile time
   └─ Methods: getType()
```

---

## STARTUP TIME

### 1. PapSdkAutoConfiguration

**Package & Location:**
```
com.example.pep.sdk.starter.PapSdkAutoConfiguration
Module: sdk-spring-boot-starter
Annotation: @AutoConfiguration
```

**Extends/Implements:**
```
None (plain Spring @Configuration class)
```

**Key Methods (All @Bean):**
```
- papEntityRegistry()
  └─ Creates PapEntityRegistry from metadata files
  
- papCatalog()
  └─ Creates PapCatalog from pap-endpoints.json

- papRequestBuilder(PapCatalog catalog, EndpointResolver resolver)
  └─ Creates PapRequestBuilder

- papSdkRestClient(PapSdkProperties props)
  └─ Creates RestClient with base URL

- papRetryPolicy()
  └─ Creates Retry policy (Resilience4j)

- papCircuitBreakerPolicy()
  └─ Creates CircuitBreaker policy (Resilience4j)

- papClient(RestClient rc, Retry retry, CircuitBreaker cb)
  └─ Creates PapClient

- papSdkSharedEntityManager(EntityManagerFactory emf)
  └─ Creates shared EntityManager proxy

- divergenceRecorder(PapDivergenceRepository repo, EntityManager em, ObjectMapper mapper, Clock clock)
  └─ Creates DivergenceRecorder

- papTransactionSynchronization(...)
  └─ Creates PapTransactionSynchronization

- papEntityListener(PapEntityRegistry registry, PapTransactionSynchronization sync)
  └─ Creates PapEntityListener

- hibernateListenerRegistrar(EntityManagerFactory emf, PapEntityListener listener)
  └─ Registers listener with Hibernate
```

**Uses:**
```
1. MetadataLoader
   ├─ Package: com.example.pep.sdk.core.registry
   ├─ Method: load(ClassLoader) → PapEntityRegistry
   └─ Calls: classLoader.getResources("META-INF/pep-sdk/metadata.json")

2. PapCatalog
   ├─ Package: com.example.pep.sdk.core.catalog
   ├─ Method: PapCatalog() → loads pap-endpoints.json
   └─ Uses: Jackson ObjectMapper.readTree()

3. PapSdkProperties (bound from application.yml)
   ├─ Package: com.example.pep.sdk.starter
   ├─ Fields: baseUrl, timeout, retry, circuitBreaker configs
   └─ Binding: @ConfigurationProperties("pap.sdk")

4. RestClient (Spring Framework)
   ├─ From: org.springframework.web.client.RestClient
   ├─ Method: RestClient.builder()
   └─ Configured with: baseUrl, requestFactory, timeouts

5. Resilience4j Components
   ├─ Package: io.github.resilience4j.retry, io.github.resilience4j.circuitbreaker
   ├─ Classes: Retry, CircuitBreaker
   └─ Via: Resilience4jFactory (custom wrapper)

6. SharedEntityManagerCreator (Spring ORM)
   ├─ From: org.springframework.orm.jpa.SharedEntityManagerCreator
   ├─ Method: createSharedEntityManager(EntityManagerFactory)
   └─ Returns: transaction-aware EntityManager proxy

7. HibernateListenerRegistrar
   ├─ Package: com.example.pep.sdk.starter
   ├─ Method: register(SessionFactory, PapEntityListener)
   └─ Via: SessionFactoryUtils.getHibernateSessionFactory(EMF)

8. PapDivergenceRepository (Spring Data JPA)
   ├─ Package: com.example.pep.sdk.sync
   ├─ Type: CrudRepository<PapDivergenceEntry, Integer>
   └─ Methods: save(), deletePendingByTransactionId(), markRejectedByTransactionId()
```

**ThreadLocal Usage:**
```
NO - This is a @Configuration class that runs at startup
All beans created are singletons
Bean dependencies are injected normally (not via ThreadLocal)
```

**Call Flow:**
```
Spring Boot AutoConfiguration discovery
  ↓
PapSdkAutoConfiguration.papEntityRegistry()
  ├─ MetadataLoader.load(Thread.currentThread().getContextClassLoader())
  │  ├─ classLoader.getResources("META-INF/pep-sdk/metadata.json")
  │  └─ For each resource: parse JSON → build PapEntityDescriptor
  └─ Return: PapEntityRegistry(@Singleton)

PapSdkAutoConfiguration.papCatalog()
  ├─ PapCatalog() constructor
  │  ├─ Load pap-endpoints.json from classpath
  │  └─ Jackson.readTree() → EndpointSpec map
  └─ Return: PapCatalog(@Singleton)

... (other bean methods) ...

PapSdkAutoConfiguration.hibernateListenerRegistrar()
  ├─ SessionFactory sf = SessionFactoryUtils.getSessionFactory(emf)
  ├─ sf.getEventListenerRegistry()
  └─ appendListeners(POST_INSERT, POST_UPDATE, POST_DELETE → papEntityListener)
```

---

### 2. MetadataLoader

**Package & Location:**
```
com.example.pep.sdk.core.registry.MetadataLoader
Module: sdk-core
```

**Key Methods:**
```
- MetadataLoader(CommunicationMode mode)
  └─ Constructor takes mode (SYNC or ASYNC)

- PapEntityRegistry load(ClassLoader cl)
  ├─ getResources("META-INF/pep-sdk/metadata.json")
  ├─ For each: parse JSON
  └─ Return: PapEntityRegistry with all descriptors
```

**Uses:**
```
1. ClassLoader (JDK)
   ├─ Method: getResources(String name)
   └─ Returns: Enumeration<URL> of all matching classpath resources

2. Jackson ObjectMapper
   ├─ Package: com.fasterxml.jackson.databind
   ├─ Method: readValue(InputStream, PapEntityDescriptor.class)
   └─ Deserializes JSON to Java objects

3. java.lang.reflect.Field (JDK)
   ├─ Purpose: Resolve dot-paths at runtime
   └─ Method: getDeclaredFields()

4. PapEntityDescriptor (builder pattern)
   ├─ Package: com.example.pep.sdk.core.model
   └─ Created from JSON metadata
```

**ThreadLocal Usage:**
```
NO - Runs once at startup in single thread
Results stored in PapEntityRegistry singleton
```

---

### 3. PapEntityRegistry

**Package & Location:**
```
com.example.pep.sdk.core.registry.PapEntityRegistry
Module: sdk-core
```

**Key Methods:**
```
- PapEntityRegistry(Map<Class<?>, PapEntityDescriptor> byClass)
  └─ Immutable map: Class<?> → PapEntityDescriptor

- Optional<PapEntityDescriptor> find(Class<?> entityClass)
  └─ Lookup by entity class
```

**Uses:**
```
1. Map<Class<?>, PapEntityDescriptor> (JDK)
   ├─ Populated by MetadataLoader
   └─ Read-only after construction
```

**ThreadLocal Usage:**
```
NO - Singleton, thread-safe read-only map
```

---

### 4. PapCatalog

**Package & Location:**
```
com.example.pep.sdk.core.catalog.PapCatalog
Module: sdk-core
```

**Key Methods:**
```
- PapCatalog()
  ├─ Load pap-endpoints.json from classpath
  └─ Parse with Jackson

- EndpointSpec endpointFor(String entityName)
  └─ Lookup endpoint by PAP entity name
```

**Uses:**
```
1. Jackson ObjectMapper
   ├─ readTree() on classpath resource
   └─ Parse JSON into Map<String, EndpointSpec>

2. Map<String, EndpointSpec>
   ├─ Stores all endpoint specifications
   └─ Key: "ResourceInstance", Value: {path, method, headers, body, ...}
```

**ThreadLocal Usage:**
```
NO - Singleton, read-only after startup
```

---

## REQUEST HANDLING TIME

### 1. ResourceController.ordered()

**Package & Location:**
```
com.example.pep.demo.ResourceController
Module: demo-service
```

**Method Signature:**
```
@PostMapping("/resources/ordered")
@Transactional
public ResponseEntity<String> ordered(...)
```

**Uses:**
```
1. PipelineRepository (Spring Data JPA)
   ├─ Package: com.example.pep.demo
   ├─ Extends: JpaRepository<Pipeline, Long>
   ├─ Method: save(Pipeline entity)
   └─ Triggers: Hibernate.persist() → POST_INSERT event

2. DeploymentRepository
   ├─ Same pattern as PipelineRepository

3. MonitorRepository
   ├─ Same pattern as PipelineRepository

4. Spring @Transactional annotation
   ├─ Package: org.springframework.transaction.annotation
   ├─ Interceptor: TransactionInterceptor (Spring AOP)
   └─ Opens TX: PlatformTransactionManager.getTransaction()
```

**ThreadLocal Usage:**
```
YES - Spring TransactionSynchronizationManager (below)
Stores transaction context in ThreadLocal
```

---

### 2. PapEntityListener.onPostInsert()

**Package & Location:**
```
com.example.pep.sdk.starter.PapEntityListener
Module: sdk-spring-boot-starter
Implements: InitializingBean, PostInsertEventListener (Hibernate)
```

**Key Method:**
```
- void onPostInsert(PostInsertEvent event)
  ├─ Triggered by Hibernate after SQL INSERT executes
  └─ Called within open business transaction
```

**Uses:**
```
1. PostInsertEvent (Hibernate)
   ├─ Package: org.hibernate.event.spi
   ├─ Method: getEntity() → persisted entity instance
   └─ Method: getSession() → current Hibernate Session

2. PapEntityRegistry
   ├─ Package: com.example.pep.sdk.core.registry
   ├─ Method: find(entity.getClass())
   └─ Returns: Optional<PapEntityDescriptor>

3. TransactionSynchronizationManager (Spring)
   ├─ Package: org.springframework.transaction.support
   ├─ Key methods:
   │  ├─ isSynchronizationActive() → boolean
   │  ├─ getResource(Object key) → Object (ThreadLocal-backed!)
   │  ├─ bindResource(Object key, Object value) → void (ThreadLocal-backed!)
   │  └─ registerSynchronization(TransactionSynchronization sync)
   └─ Storage: ThreadLocal<Set<TransactionSynchronization>>

4. ChangeBuffer
   ├─ Package: com.example.pep.sdk.sync
   ├─ Data structure: LinkedHashMap<String, PapEntityChange>
   └─ Retrieved from ThreadLocal in first onPostInsert() call

5. PapTransactionSynchronization
   ├─ Package: com.example.pep.sdk.sync
   ├─ Registered via: TransactionSynchronizationManager.registerSynchronization()
   └─ Called later in: beforeCommit(), afterCompletion()
```

**ThreadLocal Usage:**
```
YES - Direct use of TransactionSynchronizationManager

Key ThreadLocal chains:
- TransactionSynchronizationManager.getResource(BUFFER_KEY)
  └─ ThreadLocal holds: Map<Object, Object> resources
     └─ Key="com.example.pep.sdk.sync.BUFFER"
     └─ Value=ChangeBuffer instance

- TransactionSynchronizationManager.bindResource(BUFFER_KEY, buffer)
  └─ Stores in ThreadLocal for this thread/transaction

Flow:
  First entity's POST_INSERT
    ├─ getResource(BUFFER_KEY)
    ├─ Buffer is null → create new
    ├─ bindResource(BUFFER_KEY, newBuffer)  [ThreadLocal write]
    └─ registerSynchronization(papTxnSync)

  Second/Third entity's POST_INSERT
    ├─ getResource(BUFFER_KEY)  [ThreadLocal read]
    ├─ Buffer exists → append to it
    └─ No new registration (already done)
```

---

### 3. ChangeBuffer

**Package & Location:**
```
com.example.pep.sdk.sync.ChangeBuffer
Module: sdk-sync
```

**Data Structure:**
```
extends LinkedHashMap<String, PapEntityChange>
```

**Key Methods:**
```
- void append(PapEntityChange change)
  └─ LinkedHashMap.put(key, value)
  └─ Preserves insertion order

- List<PapEntityChange> drain()
  └─ Return values and clear
  └─ Called once per beforeCommit()
```

**Uses:**
```
1. LinkedHashMap (JDK)
   ├─ Extends: Map<String, PapEntityChange>
   └─ Purpose: Preserve entity creation order

2. PapEntityChange
   ├─ Package: com.example.pep.sdk.core.model
   ├─ Immutable data class: {entityClass, operation, mode, id, entity}
   └─ Created by: PapEntityListener.onPostInsert()
```

**ThreadLocal Usage:**
```
NO - The buffer itself is not ThreadLocal
BUT it's stored in ThreadLocal via TransactionSynchronizationManager
```

---

### 4. PapTransactionSynchronization.beforeCommit()

**Package & Location:**
```
com.example.pep.sdk.sync.PapTransactionSynchronization
Module: sdk-sync
Implements: TransactionSynchronization (Spring)
```

**Key Method:**
```
- void beforeCommit(boolean readOnly)
  ├─ Called by Spring BEFORE JDBC commit
  └─ Within still-open, still-rollback-able transaction
```

**Uses:**
```
1. EntityManager (shared proxy)
   ├─ Package: jakarta.persistence
   ├─ Method: flush()
   └─ Executes deferred SQL, fires POST events

2. TransactionSynchronizationManager
   ├─ Method: getResource(BUFFER_KEY)  [ThreadLocal read]
   └─ Retrieves ChangeBuffer for this thread

3. ChangeBuffer
   ├─ Method: drain()
   └─ Returns List<PapEntityChange> and clears

4. dispatch(PapEntityChange change)
   ├─ Defined in same class
   └─ Called once per entity
```

**ThreadLocal Usage:**
```
YES - Reads BUFFER_KEY from ThreadLocal
```

---

### 5. PapTransactionSynchronization.dispatch()

**Package & Location:**
```
com.example.pep.sdk.sync.PapTransactionSynchronization.dispatch()
Module: sdk-sync
```

**Key Method:**
```
- private void dispatch(PapEntityChange change)
  ├─ Build HTTP request
  ├─ Send to PAP
  └─ Record divergence if successful
```

**Uses:**
```
1. PapEntityRegistry
   ├─ Method: find(change.entityClass())
   └─ Returns: PapEntityDescriptor

2. PapRequestBuilder
   ├─ Package: com.example.pep.sdk.core.request
   ├─ Method: build(descriptor, change)
   └─ Returns: PapRequest

3. PapClient
   ├─ Package: com.example.pep.sdk.client
   ├─ Method: send(request)
   └─ Blocking call with Retry+CircuitBreaker

4. currentOrNewTransactionId()
   ├─ Private method in same class
   ├─ Uses: TransactionSynchronizationManager.getResource(TX_ID_KEY)  [ThreadLocal]
   └─ Uses: DivergenceRecorder.nextTransactionId()

5. DivergenceRecorder
   ├─ Package: com.example.pep.sdk.sync
   ├─ Method: recordPending(txId, entityType, operation, payload)
   └─ @Transactional(REQUIRES_NEW) — separate TX!
```

**ThreadLocal Usage:**
```
YES - Uses currentOrNewTransactionId()
├─ Reads TX_ID_KEY from ThreadLocal
├─ If null: generates ID and bindResource(TX_ID_KEY, txId)
└─ Stores in ThreadLocal for afterCompletion()
```

---

### 6. PapRequestBuilder.build()

**Package & Location:**
```
com.example.pep.sdk.core.request.PapRequestBuilder
Module: sdk-core
```

**Key Method:**
```
- PapRequest build(PapEntityDescriptor descriptor, PapEntityChange change)
  ├─ Resolve source values
  ├─ Look up endpoint spec
  └─ Build HTTP request object
```

**Uses:**
```
1. PapEntityDescriptor
   ├─ Method: resolveSources(entity)
   └─ Returns: Map<String, Object> of all source values

2. PapCatalog
   ├─ Method: endpointFor(papEntity)
   └─ Returns: EndpointSpec {path, method, headers, body, ...}

3. EndpointResolver
   ├─ Package: com.example.pep.sdk.core.request
   ├─ Method: resolve(sources, spec)
   └─ Maps sources to request components

4. PapRequest
   ├─ Package: com.example.pep.sdk.core.request
   ├─ Immutable: {method, path, headers, body}
   └─ Created here
```

**ThreadLocal Usage:**
```
NO
```

---

### 7. PapClient.send()

**Package & Location:**
```
com.example.pep.sdk.client.PapClient
Module: sdk-client
```

**Key Method:**
```
- void send(PapRequest request)
  ├─ Wrapped with Retry
  ├─ Wrapped with CircuitBreaker
  └─ Calls: doSend(request)
```

**Uses:**
```
1. io.github.resilience4j.retry.Retry
   ├─ Package: io.github.resilience4j:resilience4j-retry
   ├─ Method: executeSupplier(Supplier<V> supplier)
   └─ Retries on exception

2. io.github.resilience4j.circuitbreaker.CircuitBreaker
   ├─ Package: io.github.resilience4j:resilience4j-core
   ├─ Method: executeSupplier(Supplier<V> supplier)
   └─ Checks circuit state before call

3. RestClient (Spring Framework)
   ├─ Package: org.springframework.web.client
   ├─ Method: post().uri(url).headers(...).body(...).retrieve()
   └─ Makes HTTP call

4. HttpHeaders
   ├─ Package: org.springframework.http
   ├─ Purpose: Set request headers
   └─ Method: set(name, value)

5. jackson ObjectMapper
   ├─ Package: com.fasterxml.jackson.databind
   ├─ Purpose: Serialize body to JSON
   └─ Method: writeValueAsString(Object)
```

**ThreadLocal Usage:**
```
NO - Pure REST call
Resilience4j may use ThreadLocal internally for state tracking
(Not relevant to our architecture)
```

---

### 8. DivergenceRecorder

**Package & Location:**
```
com.example.pep.sdk.sync.DivergenceRecorder
Module: sdk-sync
```

**Key Methods:**
```
- int nextTransactionId()
  ├─ @Transactional(REQUIRES_NEW)
  └─ SELECT nextval('pap_divergence_transaction_seq')

- void recordPending(int txId, String entityType, String operation, Map payload)
  ├─ @Transactional(REQUIRES_NEW)
  └─ INSERT into STL_PEP_DIVERGENCE status=PENDING

- void resolveCommitted(int txId)
  ├─ @Transactional(REQUIRES_NEW)
  └─ DELETE FROM STL_PEP_DIVERGENCE WHERE transaction_id=txId

- void resolveRolledBack(int txId, String reason, String message)
  ├─ @Transactional(REQUIRES_NEW)
  └─ UPDATE to REJECTED_DATA WHERE transaction_id=txId
```

**Uses:**
```
1. EntityManager (shared proxy)
   ├─ Package: jakarta.persistence
   ├─ Method: createNativeQuery(String sql)
   └─ For sequence nextval()

2. PapDivergenceRepository
   ├─ Package: com.example.pep.sdk.sync
   ├─ Extends: JpaRepository<PapDivergenceEntry, Integer>
   ├─ Methods:
   │  ├─ save(PapDivergenceEntry entry)
   │  ├─ deletePendingByTransactionId(int txId) [custom]
   │  └─ markRejectedByTransactionId(int txId, String error) [custom]
   └─ Wired via: Spring Data JPA

3. PapDivergenceEntry
   ├─ Package: com.example.pep.sdk.sync
   ├─ @Entity, @Table(name="STL_PEP_DIVERGENCE")
   ├─ Fields: transactionId, entityType, operation, payload, status, error
   └─ Created here: new PapDivergenceEntry(...)

4. ObjectMapper
   ├─ Package: com.fasterxml.jackson.databind
   ├─ Method: writeValueAsString(Object)
   └─ Serialize request/error to JSON

5. Clock (JDK)
   ├─ Package: java.time
   ├─ Method: instant()
   └─ For created_at timestamp
```

**ThreadLocal Usage:**
```
NO - But runs in REQUIRES_NEW transaction

Key aspect: REQUIRES_NEW suspends/resumes the business TX
Spring's TransactionManager handles this via:
  └─ TransactionSynchronizationManager.bindResource() / getResource()
     └─ Different transaction context (new TX opened)
     └─ New entry in ThreadLocal resources map
```

---

### 9. PapTransactionSynchronization.afterCompletion()

**Package & Location:**
```
com.example.pep.sdk.sync.PapTransactionSynchronization.afterCompletion()
Module: sdk-sync
Implements: TransactionSynchronization
```

**Key Method:**
```
- void afterCompletion(int status)
  ├─ Called by Spring AFTER transaction outcome known
  ├─ status = STATUS_COMMITTED or STATUS_ROLLED_BACK
  └─ Not in transaction context (no active TX)
```

**Uses:**
```
1. TransactionSynchronizationManager
   ├─ Method: getResource(TX_ID_KEY)  [ThreadLocal read]
   └─ Retrieves transaction_id bound during dispatch()

2. DivergenceRecorder
   ├─ Method: resolveCommitted(txId) if status==COMMITTED
   ├─ OR: resolveRolledBack(txId, reason, message) if status==ROLLED_BACK
   └─ Both are @Transactional(REQUIRES_NEW)

3. TransactionSynchronizationManager (cleanup)
   ├─ Method: unbindResource(TX_ID_KEY)  [ThreadLocal write]
   ├─ Method: unbindResource(BUFFER_KEY)  [ThreadLocal write]
   └─ Clear thread-local resources after transaction
```

**ThreadLocal Usage:**
```
YES - Reads and unbinds from ThreadLocal
```

---

## ThreadLocal Summary Table

| Component | Class | Method/Field | ThreadLocal Key | Usage |
|-----------|-------|--------------|-----------------|-------|
| TransactionContext | TransactionSynchronizationManager | resources | BUFFER_KEY | Store ChangeBuffer per thread/TX |
| TransactionContext | TransactionSynchronizationManager | resources | TX_ID_KEY | Store transaction_id per thread/TX |
| Synchronization | TransactionSynchronizationManager | synchronizations | (internal) | Register beforeCommit/afterCompletion callbacks |
| Divergence | DivergenceRecorder | (via TX) | (via Spring TM) | REQUIRES_NEW creates separate TX context |

---

## Package Organization

```
sdk-core (core models, no Spring)
├─ com.example.pep.sdk.core.annotation.*
│  └─ @PapEntity, @PapAttribute, @PapInclude, @PapProperty, @PapOperationMode
├─ com.example.pep.sdk.core.model.*
│  └─ PapEntityDescriptor, PapEntityChange, Operation, HttpMethod, ChangeBuffer
├─ com.example.pep.sdk.core.catalog.*
│  └─ PapCatalog, EndpointSpec, OperationSpec
├─ com.example.pep.sdk.core.registry.*
│  └─ PapEntityRegistry, MetadataLoader
├─ com.example.pep.sdk.core.request.*
│  └─ PapRequest, PapRequestBuilder, EndpointResolver
└─ com.example.pep.sdk.core.exception.*
   └─ PapSdkException, PapRejectedException, PapUnavailableException

sdk-processor (compile-time, no Spring)
├─ com.example.pep.sdk.processor.PapAnnotationProcessor
├─ com.example.pep.sdk.processor.CompileTimeCatalog
└─ com.example.pep.sdk.processor.DotPathValidator

sdk-client (HTTP client, no JPA)
└─ com.example.pep.sdk.client.PapClient

sdk-sync (SYNC-only runtime, Spring-integrated)
├─ com.example.pep.sdk.sync.ChangeBuffer
├─ com.example.pep.sdk.sync.PapTransactionSynchronization
├─ com.example.pep.sdk.sync.DivergenceRecorder
├─ com.example.pep.sdk.sync.PapDivergenceEntry (@Entity)
└─ com.example.pep.sdk.sync.PapDivergenceRepository

sdk-spring-boot-starter (Spring AutoConfiguration)
├─ com.example.pep.sdk.starter.PapSdkAutoConfiguration
├─ com.example.pep.sdk.starter.PapEntityListener (Hibernate listener)
├─ com.example.pep.sdk.starter.HibernateListenerRegistrar
├─ com.example.pep.sdk.starter.PapSdkProperties (@ConfigurationProperties)
└─ com.example.pep.sdk.starter.Resilience4jFactory

demo-service (application code)
├─ com.example.pep.demo.ResourceController
├─ com.example.pep.demo.Pipeline, Deployment, Monitor (@PapEntity entities)
├─ com.example.pep.demo.Tenant (plain @Entity)
└─ com.example.pep.demo.Repositories.java
```

---

## External Dependencies Map

```
Dependencies by Package:

Spring Framework
├─ org.springframework.transaction.support.TransactionSynchronizationManager
├─ org.springframework.transaction.annotation.@Transactional, Propagation
├─ org.springframework.web.client.RestClient
├─ org.springframework.orm.jpa.SharedEntityManagerCreator
├─ org.springframework.boot.autoconfigure.@AutoConfiguration
├─ org.springframework.boot.context.properties.@ConfigurationProperties
└─ org.springframework.transaction.PlatformTransactionManager

Spring Data JPA
├─ org.springframework.data.jpa.repository.JpaRepository
└─ org.springframework.data.jpa.repository.config.@EnableJpaRepositories

Jakarta Persistence
├─ jakarta.persistence.@Entity, @Id, @Transactional
├─ jakarta.persistence.EntityManager, EntityManagerFactory
└─ jakarta.persistence.@PersistenceContext

Hibernate
├─ org.hibernate.event.spi.PostInsertEvent, PostUpdateEvent, PostDeleteEvent
├─ org.hibernate.event.spi.EventListenerRegistry
└─ org.hibernate.Session, SessionFactory

Resilience4j
├─ io.github.resilience4j.retry.Retry
└─ io.github.resilience4j.circuitbreaker.CircuitBreaker

Jackson (JSON serialization)
└─ com.fasterxml.jackson.databind.ObjectMapper

Flyway (schema migration)
└─ org.flywaydb.core

PostgreSQL JDBC
└─ org.postgresql.Driver

Java Standard Library
├─ java.lang.reflect.Field
├─ java.time.Clock, Instant
├─ java.util.* (Map, List, LinkedHashMap, etc.)
└─ javax.annotation.processing.* (compile-time only)
```

---

## Method Call Chain Example: One Entity Dispatch

```
ResourceController.ordered()  [com.example.pep.demo]
  └─ @Transactional interceptor opens TX
  
  └─ pipelines.save(entity)  [JpaRepository]
     └─ Hibernate.Session.persist()
        └─ SQL queued (INSERT into pipeline)
        
        └─ Hibernate POST_INSERT event fires
           
           └─ PapEntityListener.onPostInsert()  [com.example.pep.sdk.starter]
              ├─ TransactionSynchronizationManager.isSynchronizationActive()  [ThreadLocal check]
              ├─ TransactionSynchronizationManager.getResource(BUFFER_KEY)  [ThreadLocal read]
              │  └─ Returns: null (first entity)
              │
              ├─ new ChangeBuffer()  [com.example.pep.sdk.sync]
              ├─ TransactionSynchronizationManager.bindResource(BUFFER_KEY, buffer)  [ThreadLocal write]
              ├─ TransactionSynchronizationManager.registerSynchronization(papTxnSync)
              │  └─ Registers: PapTransactionSynchronization.beforeCommit()
              │              PapTransactionSynchronization.afterCompletion()
              │
              └─ buffer.append(new PapEntityChange(...))  [LinkedHashMap write]
  
  └─ [method returns, TX still open]
  
  └─ Spring TransactionManager.commit()
     └─ TransactionManager.triggerBeforeCommit()
        
        └─ PapTransactionSynchronization.beforeCommit()  [com.example.pep.sdk.sync]
           ├─ entityManager.flush()  [jakarta.persistence]
           │  └─ Executes: INSERT into pipeline (SQL)
           │
           ├─ TransactionSynchronizationManager.getResource(BUFFER_KEY)  [ThreadLocal read]
           │  └─ Returns: ChangeBuffer with 1 entity
           │
           ├─ for (change : buffer.drain())
           │  
           │  └─ dispatch(change)  [private method in same class]
           │     ├─ PapEntityRegistry.find(Pipeline.class)  [com.example.pep.sdk.core.registry]
           │     │  └─ Returns: PapEntityDescriptor
           │     │
           │     ├─ PapRequestBuilder.build(descriptor, change)  [com.example.pep.sdk.core.request]
           │     │  ├─ descriptor.resolveSources(entity)  [com.example.pep.sdk.core.model]
           │     │  │  └─ Returns: {id:1, code:"PIPE-1", ..., tenant_id:7}
           │     │  │
           │     │  ├─ PapCatalog.endpointFor("ResourceInstance")  [com.example.pep.sdk.core.catalog]
           │     │  │  └─ Returns: EndpointSpec {path, method, headers, body}
           │     │  │
           │     │  └─ Returns: PapRequest
           │     │
           │     ├─ PapClient.send(request)  [com.example.pep.sdk.client]
           │     │  ├─ Retry.executeSupplier(...)  [io.github.resilience4j.retry]
           │     │  │  └─ Retries up to 3 times
           │     │  │
           │     │  └─ CircuitBreaker.executeSupplier(...)  [io.github.resilience4j.circuitbreaker]
           │     │     └─ doSend(request)
           │     │        ├─ RestClient.post()  [org.springframework.web.client]
           │     │        │  ├─ .uri("http://localhost:8080/pap/v1/resource-instances")
           │     │        │  ├─ .headers(headers)
           │     │        │  ├─ .body(requestBody)
           │     │        │  └─ .retrieve().toBodilessEntity()  [HTTP POST]
           │     │        │
           │     │        └─ Check status → 2xx OK ✓
           │     │
           │     ├─ currentOrNewTransactionId()  [private method]
           │     │  ├─ TransactionSynchronizationManager.getResource(TX_ID_KEY)  [ThreadLocal read]
           │     │  │  └─ Returns: null (first dispatch)
           │     │  │
           │     │  ├─ DivergenceRecorder.nextTransactionId()  [com.example.pep.sdk.sync]
           │     │  │  ├─ @Transactional(REQUIRES_NEW)  [Suspends business TX]
           │     │  │  ├─ EntityManager.createNativeQuery("SELECT nextval(...)")
           │     │  │  ├─ Returns: 41
           │     │  │  └─ COMMIT divergence TX (restores business TX)
           │     │  │
           │     │  ├─ TransactionSynchronizationManager.bindResource(TX_ID_KEY, 41)  [ThreadLocal write]
           │     │  └─ Returns: 41
           │     │
           │     └─ DivergenceRecorder.recordPending(41, "ResourceInstance", "CREATE", requestShape)  [com.example.pep.sdk.sync]
           │        ├─ @Transactional(REQUIRES_NEW)  [Suspends business TX]
           │        ├─ ObjectMapper.writeValueAsString(requestShape)  [com.fasterxml.jackson.databind]
           │        ├─ new PapDivergenceEntry(41, "ResourceInstance", "CREATE", json, now(), "PENDING")
           │        ├─ PapDivergenceRepository.save(entry)  [Spring Data JPA]
           │        │  └─ EntityManager.persist() → INSERT into STL_PEP_DIVERGENCE
           │        │
           │        └─ COMMIT divergence TX (restores business TX)
  
  └─ [beforeCommit returns without exception]
  
  └─ Spring TransactionManager.doCommit()
     └─ JDBC connection.commit()
        └─ All INSERTs committed atomically
  
  └─ Spring TransactionManager.triggerAfterCompletion()
     
     └─ PapTransactionSynchronization.afterCompletion(STATUS_COMMITTED)  [com.example.pep.sdk.sync]
        ├─ TransactionSynchronizationManager.getResource(TX_ID_KEY)  [ThreadLocal read]
        │  └─ Returns: 41
        │
        ├─ DivergenceRecorder.resolveCommitted(41)  [com.example.pep.sdk.sync]
        │  ├─ @Transactional(REQUIRES_NEW)
        │  ├─ PapDivergenceRepository.deletePendingByTransactionId(41)  [custom query]
        │  │  └─ DELETE FROM STL_PEP_DIVERGENCE WHERE transaction_id=41
        │  └─ COMMIT divergence TX
        │
        ├─ TransactionSynchronizationManager.unbindResource(TX_ID_KEY)  [ThreadLocal cleanup]
        └─ TransactionSynchronizationManager.unbindResource(BUFFER_KEY)  [ThreadLocal cleanup]

  └─ HTTP 200 OK
```

