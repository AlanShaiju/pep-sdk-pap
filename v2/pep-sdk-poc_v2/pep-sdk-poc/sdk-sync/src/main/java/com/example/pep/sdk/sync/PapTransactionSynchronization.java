package com.example.pep.sdk.sync;

import com.example.pep.sdk.client.PapClient;
import com.example.pep.sdk.core.annotation.CommunicationMode;
import com.example.pep.sdk.core.exception.PapRejectedException;
import com.example.pep.sdk.core.exception.PapSdkException;
import com.example.pep.sdk.core.exception.PapUnavailableException;
import com.example.pep.sdk.core.model.PapEntityChange;
import com.example.pep.sdk.core.model.PapEntityDescriptor;
import com.example.pep.sdk.core.registry.PapEntityRegistry;
import com.example.pep.sdk.core.request.PapRequest;
import com.example.pep.sdk.core.request.PapRequestBuilder;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PapTransactionSynchronization implements TransactionSynchronization {

    private static final Logger log = LoggerFactory.getLogger(PapTransactionSynchronization.class);
    public static final String BUFFER_KEY = "com.example.pep.sdk.sync.BUFFER";
    private static final String TX_ID_KEY = "com.example.pep.sdk.sync.DIVERGENCE_TX_ID";

    private final PapEntityRegistry registry;
    private final PapRequestBuilder requestBuilder;
    private final PapClient papClient;
    private final OutboxAppender outboxAppender;
    private final DivergenceRecorder divergenceRecorder;
    private final EntityManager entityManager;

    public PapTransactionSynchronization(PapEntityRegistry registry,
                                         PapRequestBuilder requestBuilder,
                                         PapClient papClient,
                                         OutboxAppender outboxAppender,
                                         DivergenceRecorder divergenceRecorder,
                                         EntityManager entityManager) {
        this.registry = registry;
        this.requestBuilder = requestBuilder;
        this.papClient = papClient;
        this.outboxAppender = outboxAppender;
        this.divergenceRecorder = divergenceRecorder;
        this.entityManager = entityManager;
    }

    @Override
    public void beforeCommit(boolean readOnly) {
        if (readOnly) return;

        // Required, not optional: Spring's transaction manager calls this synchronization's
        // beforeCommit() BEFORE JpaTransactionManager.doCommit(), and doCommit() is what
        // triggers Hibernate's own flush. Without forcing it here, deferred UPDATE/DELETE SQL
        // has not executed yet, the listener has not fired, and the buffer would be incomplete.
        entityManager.flush();

        ChangeBuffer buffer = (ChangeBuffer) TransactionSynchronizationManager.getResource(BUFFER_KEY);
        if (buffer == null || buffer.isEmpty()) return;

        int txId = divergenceRecorder.nextTransactionId();
        TransactionSynchronizationManager.bindResource(TX_ID_KEY, txId);

        for (PapEntityChange change : buffer.drain()) {
            dispatch(change, txId);
        }
    }

    @Override
    public void afterCompletion(int status) {
        Integer txId = (Integer) TransactionSynchronizationManager.getResource(TX_ID_KEY);
        if (txId != null) {
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                divergenceRecorder.resolveCommitted(txId);
            } else {
                // STATUS_ROLLED_BACK or STATUS_UNKNOWN: treat as rolled back — the local
                // transaction did not durably persist, so anything PAP already accepted is
                // now stale there. (afterCompletion's int status carries no causing exception;
                // see design doc §12.1 for why "error" content here is necessarily generic.)
                divergenceRecorder.resolveRolledBack(txId, "LOCAL_TRANSACTION_NOT_COMMITTED",
                        "Transaction completed with status " + status + "; PAP write(s) already succeeded.");
            }
            TransactionSynchronizationManager.unbindResource(TX_ID_KEY);
        }
        if (TransactionSynchronizationManager.hasResource(BUFFER_KEY)) {
            TransactionSynchronizationManager.unbindResource(BUFFER_KEY);
        }
    }

    private void dispatch(PapEntityChange change, int txId) {
        PapEntityDescriptor descriptor = registry.find(change.entityClass())
                .orElseThrow(() -> new PapSdkException("No descriptor for " + change.entityClass().getName()));

        if (change.mode() == CommunicationMode.SYNC) {
            PapRequest req = requestBuilder.build(descriptor, change);
            log.info("SYNC dispatch: {} {} (entity={})", req.method(), req.path(), change.entityId());
            papClient.send(req); // PapRejectedException/PapUnavailableException -> rolls back; nothing recorded
            divergenceRecorder.recordPending(txId, descriptor.papEntity(), change.operation().name(),
                    fullRequestShape(req));
        } else {
            log.info("ASYNC enqueue: {} {} (entity={})", change.operation(), descriptor.papEntity(), change.entityId());
            outboxAppender.append(change, descriptor);
        }
    }

    /**
     * Full resolved request shape (body + headers + pathVariables + queryParams), not just
     * the body. A DELETE's catalog-defined body is typically empty — if the divergence row
     * only captured the body, a DELETE divergence row would carry no identifying information
     * at all. Capturing everything means the id (wherever the catalog put it) is always present.
     */
    private static Map<String, Object> fullRequestShape(PapRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", req.path());
        out.put("pathVariables", req.pathVariables());
        out.put("headers", req.headers());
        out.put("queryParams", req.requestParams());
        out.put("body", req.payload());
        return out;
    }
}
