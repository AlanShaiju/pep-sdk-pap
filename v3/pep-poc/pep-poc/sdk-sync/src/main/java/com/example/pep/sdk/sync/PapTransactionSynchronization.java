package com.example.pep.sdk.sync;

import com.example.pep.sdk.client.PapClient;
import com.example.pep.sdk.core.annotation.CommunicationMode;
import com.example.pep.sdk.core.exception.PapSdkException;
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

/**
 * Drains the transaction-scoped ChangeBuffer at beforeCommit and dispatches each change to the
 * PAP synchronously, in buffer (insertion) order. SYNC only — this POC has no async path.
 *
 * <p>Divergence handling (STL_PEP_DIVERGENCE):
 * <ul>
 *   <li>The first successful PAP call in a transaction generates and binds a transaction_id
 *       (lazily -- a transaction whose calls are all rejected before any success never needs
 *       one), then writes a PENDING divergence row immediately (durable, own REQUIRES_NEW
 *       transaction). Every later successful call in the same transaction reuses that id.</li>
 *   <li>A PAP rejection throws -> the whole local transaction rolls back. Nothing was written
 *       to the PAP for the rejected entity, so no row for it; rows already written for earlier
 *       entities in this transaction are resolved as rolled-back below.</li>
 *   <li>afterCompletion(COMMITTED): delete this transaction's PENDING rows (no real divergence).</li>
 *   <li>afterCompletion(ROLLED_BACK/UNKNOWN): flip this transaction's PENDING rows to REJECTED_DATA.</li>
 * </ul>
 */
public final class PapTransactionSynchronization implements TransactionSynchronization {

    private static final Logger log = LoggerFactory.getLogger(PapTransactionSynchronization.class);
    public static final String BUFFER_KEY = "com.example.pep.sdk.sync.BUFFER";
    private static final String TX_ID_KEY = "com.example.pep.sdk.sync.DIVERGENCE_TX_ID";

    private final PapEntityRegistry registry;
    private final PapRequestBuilder requestBuilder;
    private final PapClient papClient;
    private final DivergenceRecorder divergenceRecorder;
    private final EntityManager entityManager;

    public PapTransactionSynchronization(PapEntityRegistry registry,
                                         PapRequestBuilder requestBuilder,
                                         PapClient papClient,
                                         DivergenceRecorder divergenceRecorder,
                                         EntityManager entityManager) {
        this.registry = registry;
        this.requestBuilder = requestBuilder;
        this.papClient = papClient;
        this.divergenceRecorder = divergenceRecorder;
        this.entityManager = entityManager;
    }

    @Override
    public void beforeCommit(boolean readOnly) {
        if (readOnly) return;

        // Required, not optional: Spring's transaction manager runs triggerBeforeCommit()
        // (this method) BEFORE JpaTransactionManager.doCommit(), and doCommit() is what triggers
        // Hibernate's own flush. Without forcing the flush here, deferred UPDATE/DELETE SQL has
        // not executed yet, the listener has not fired, and the buffer would be incomplete.
        // This executes SQL inside the still-open, still-rollback-able transaction; it does NOT
        // commit anything.
        entityManager.flush();

        ChangeBuffer buffer = (ChangeBuffer) TransactionSynchronizationManager.getResource(BUFFER_KEY);
        if (buffer == null || buffer.isEmpty()) return;

        // transaction_id is generated lazily -- only on the first successful PAP call, inside
        // dispatch() below -- not unconditionally here. A transaction whose first (or only)
        // entity is rejected immediately never needs an id at all, so it never pays for one.
        for (PapEntityChange change : buffer.drain()) {
            dispatch(change);
        }
    }

    @Override
    public void afterCompletion(int status) {
        Integer txId = (Integer) TransactionSynchronizationManager.getResource(TX_ID_KEY);
        if (txId != null) {
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                divergenceRecorder.resolveCommitted(txId);
            } else {
                divergenceRecorder.resolveRolledBack(txId, "LOCAL_TRANSACTION_NOT_COMMITTED",
                        "Transaction completed with status " + status + "; PAP write(s) already succeeded.");
            }
            TransactionSynchronizationManager.unbindResource(TX_ID_KEY);
        }
        if (TransactionSynchronizationManager.hasResource(BUFFER_KEY)) {
            TransactionSynchronizationManager.unbindResource(BUFFER_KEY);
        }
    }

    private void dispatch(PapEntityChange change) {
        PapEntityDescriptor descriptor = registry.find(change.entityClass())
                .orElseThrow(() -> new PapSdkException("No descriptor for " + change.entityClass().getName()));

        if (change.mode() != CommunicationMode.SYNC) {
            throw new PapSdkException("This POC is SYNC-only; entity " + descriptor.papEntity()
                    + " resolved mode " + change.mode() + " for " + change.operation());
        }

        PapRequest req = requestBuilder.build(descriptor, change);
        log.info("SYNC dispatch: {} {} (entity={}, papEntity={})",
                req.method(), req.path(), change.entityId(), descriptor.papEntity());
        papClient.send(req); // throws on 4xx/5xx -> rolls back the transaction; nothing recorded for this entity

        int txId = currentOrNewTransactionId();
        divergenceRecorder.recordPending(txId, descriptor.papEntity(), change.operation().name(),
                fullRequestShape(req));
    }

    /**
     * Returns this transaction's id if one was already generated by an earlier successful
     * dispatch in the same beforeCommit() call, otherwise generates one now (the first time it's
     * actually needed) and binds it for afterCompletion to find later.
     */
    private int currentOrNewTransactionId() {
        Integer existing = (Integer) TransactionSynchronizationManager.getResource(TX_ID_KEY);
        if (existing != null) return existing;
        int txId = divergenceRecorder.nextTransactionId();
        TransactionSynchronizationManager.bindResource(TX_ID_KEY, txId);
        return txId;
    }

    /**
     * Full resolved request shape (body + headers + pathVariables + queryParams), not just the
     * body — a DELETE's catalog-defined body is typically empty, so capturing only the body would
     * leave a DELETE divergence row with no identifying information. This way the id (wherever the
     * catalog placed it) is always present.
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
