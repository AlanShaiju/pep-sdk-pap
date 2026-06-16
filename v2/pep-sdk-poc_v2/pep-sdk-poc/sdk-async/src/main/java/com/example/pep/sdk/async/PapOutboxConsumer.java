package com.example.pep.sdk.async;

import com.example.pep.sdk.client.PapClient;
import com.example.pep.sdk.core.catalog.OperationSpec;
import com.example.pep.sdk.core.catalog.PapCatalog;
import com.example.pep.sdk.core.exception.PapRejectedException;
import com.example.pep.sdk.core.exception.PapSdkException;
import com.example.pep.sdk.core.exception.PapUnavailableException;
import com.example.pep.sdk.core.model.HttpMethod;
import com.example.pep.sdk.core.model.Operation;
import com.example.pep.sdk.core.request.EndpointResolver;
import com.example.pep.sdk.core.request.PapRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Scheduled poller. Claims a batch of PENDING rows, reconstructs each request, dispatches via
 * PapClient, and transitions row status.
 *
 * The path template is not stored on the row — the consumer reads it from the catalog using
 * entity_type and operation, then substitutes placeholders from the stored path_variable JSON.
 */
public final class PapOutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(PapOutboxConsumer.class);

    private final PapOutboxRepository repository;
    private final PapClient papClient;
    private final PapCatalog catalog;
    private final EndpointResolver endpointResolver;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    public PapOutboxConsumer(PapOutboxRepository repository,
                             PapClient papClient,
                             PapCatalog catalog,
                             EndpointResolver endpointResolver,
                             ObjectMapper objectMapper,
                             TransactionTemplate txTemplate,
                             Clock clock,
                             int batchSize,
                             int maxAttempts) {
        this.repository = repository;
        this.papClient = papClient;
        this.catalog = catalog;
        this.endpointResolver = endpointResolver;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    public void tick() {
        try {
            txTemplate.executeWithoutResult(s -> processBatch());
        } catch (Exception e) {
            log.warn("Outbox tick failed: {}", e.getMessage());
        }
    }

    private void processBatch() {
        List<PapOutboxEntry> rows = repository.claimBatch(batchSize);
        if (rows.isEmpty()) return;
        log.debug("Outbox claimed {} rows", rows.size());
        for (PapOutboxEntry row : rows) dispatchOne(row);
    }

    private void dispatchOne(PapOutboxEntry row) {
        try {
            OperationSpec opSpec = catalog.endpointFor(row.getEntityType()).forOperation(row.getOperation());
            if (opSpec == null) {
                throw new PapSdkException("No catalog entry for " + row.getEntityType() + "." + row.getOperation());
            }

            Map<String, Object> body        = parseObjectMap(row.getPayload());
            Map<String, String> headers     = parseStringMap(row.getHeader());
            Map<String, String> pathVars    = parseStringMap(row.getPathVariable());
            Map<String, String> queryParams = parseStringMap(row.getRequestParam());

            String path = endpointResolver.resolve(opSpec.path(), pathVars);

            PapRequest req = new PapRequest(
                    methodFor(row.getOperation()),
                    path,
                    body,
                    headers,
                    pathVars,
                    queryParams,
                    row.getEntityType(),
                    pathVars.getOrDefault("id", ""),
                    row.getOperation());

            log.info("ASYNC dispatch: {} {} (entity={}, row={})",
                    req.method(), req.path(), row.getEntityType(), row.getId());
            papClient.send(req);
            repository.delete(row);
        } catch (PapRejectedException e) {
            row.markRejected(clock.instant());
            repository.save(row);
            log.warn("Outbox row {} -> REJECTED_DATA: {}", row.getId(), e.reason());
        } catch (PapUnavailableException e) {
            row.incrementAttempt(clock.instant());
            if (row.getAttemptCount() >= maxAttempts) {
                row.markDeadLetter(clock.instant());
                log.warn("Outbox row {} -> DEAD_LETTER (attempts exhausted)", row.getId());
            } else {
                log.warn("Outbox row {} attempt {} failed: {}",
                        row.getId(), row.getAttemptCount(), e.getMessage());
            }
            repository.save(row);
        }
    }

    private static HttpMethod methodFor(Operation op) {
        return switch (op) {
            case CREATE -> HttpMethod.POST;
            case UPDATE -> HttpMethod.PATCH;
            case DELETE -> HttpMethod.DELETE;
        };
    }

    private Map<String, Object> parseObjectMap(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { throw new PapSdkException("Bad JSON in outbox: " + json, e); }
    }

    private Map<String, String> parseStringMap(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { throw new PapSdkException("Bad JSON in outbox: " + json, e); }
    }
}
