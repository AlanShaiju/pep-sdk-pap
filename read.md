# PEP SDK for PAP (Policy Administration Point)

A robust, enterprise-ready Java SDK designed to synchronize client policy states and security attributes with a central Policy Administration Point (PAP). The SDK provides declarative integration with Spring AOP, transactional outbox fallbacks for resilience, and circuit-breaker mechanics.

## 📦 Project Structure

The project is structured as a multi-module Maven project:

*   **`pep-sdk-pap-core`**: Core annotations, exception hierarchy, payload models, and the PAP registry.
*   **`pep-sdk-pap-spring`**: Spring framework integration. Provides the AOP Aspect (`NotifyPapAspect`), custom client configurations (`PapClient`, `RestTemplatePapClient`), Auto-configuration, and response/mapping registries.
*   **`pep-sdk-pap-outbox`**: Transactional outbox pattern fallback support. Captures failed sync requests to local database-backed outbox records and retries them via a background scheduler relay (`PapOutboxRelay`).

## 🚀 Key Features

*   **Declarative Synchronization**: Annotate your service methods with `@NotifyPap` to automatically trigger policy synchronization after successful method execution.
*   **Flexible Failure Handling (`FailBehavior`)**:
    *   `FALLBACK_TO_OUTBOX`: Saves failed synchronization events to a local outbox database table to ensure eventual consistency.
    *   `COMPENSATE`: Executes a compensating method on failure to rollback local changes or initiate a correction routine.
    *   `LOG_AND_CONTINUE`: Logs the synchronization failure and allows the business transaction to complete unaffected.
*   **Asynchronous Lifecycle Processing**: Run synchronization asynchronously with simple property configuration (`pep.pap.async-enabled=true`).
*   **Built-in Resilience**: Configurable REST Client featuring connection timeouts and a state-based circuit breaker to avoid cascading failures.

## 🛠️ Configuration Properties

Configure the SDK in your `application.properties` / `application.yml` file:

| Property | Default Value | Description |
| :--- | :--- | :--- |
| `pep.pap.base-url` | `http://localhost:8080` | The endpoint URL of the central PAP server |
| `pep.pap.async-enabled` | `false` | Enable asynchronous execution of the PAP notification lifecycle |
| `pep.pap.connection-timeout-ms` | `5000` | HTTP client connection timeout duration |
| `pep.pap.read-timeout-ms` | `5000` | HTTP client socket read timeout duration |
| `pep.pap.circuit-breaker.failure-threshold` | `5` | Threshold of consecutive errors before opening the circuit |
| `pep.pap.circuit-breaker.reset-timeout-ms` | `10000` | Time delay before attempting a half-open state check |

## 📖 Quick Start

### 1. Annotate Service Methods
Decorate service methods whose actions require synchronization:

```java
@Service
public class UserService {

    @NotifyPap(
        entity = PapEntity.USER,
        event = PapEvent.CREATE,
        failBehavior = FailBehavior.FALLBACK_TO_OUTBOX
    )
    public User createUser(UserDto dto) {
        // Business logic to create user
        return savedUser;
    }
}
```

### 2. Register a Payload Mapper
Implement the `PapPayloadMapper` interface to transform your service return value and method arguments into a `PolicyDataPayload`:

```java
@Component
public class UserCreatedMapper implements PapPayloadMapper {
    @Override
    public PolicyDataPayload map(Object result, Object[] args) {
        User user = (User) result;
        return PolicyDataPayload.builder()
                .entityId(user.getId())
                .attribute("username", user.getUsername())
                .build();
    }
}
```
