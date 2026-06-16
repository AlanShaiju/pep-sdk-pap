package com.example.pep.sdk.async;

import com.example.pep.sdk.core.exception.PapSdkException;
import com.example.pep.sdk.sync.DivergenceRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

/**
 * Every method here is REQUIRES_NEW: recordPending must commit independently of the caller's
 * (business) transaction, since that transaction's fate is exactly what's still unknown when
 * this is called. resolveCommitted/resolveRolledBack run from afterCompletion, which has no
 * active transaction at all — REQUIRES_NEW opens one.
 */
public final class DefaultDivergenceRecorder implements DivergenceRecorder {

    private final PapDivergenceRepository repository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DefaultDivergenceRecorder(PapDivergenceRepository repository,
                                     EntityManager entityManager,
                                     ObjectMapper objectMapper,
                                     Clock clock) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int nextTransactionId() {
        // Postgres/H2-compatible sequence, defined in the V2 migration.
        Number value = (Number) entityManager
                .createNativeQuery("SELECT nextval('pap_divergence_transaction_seq')")
                .getSingleResult();
        return value.intValue();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPending(int transactionId, String entityType, String operation, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            repository.save(new PapDivergenceEntry(transactionId, entityType, operation, json, clock.instant()));
        } catch (Exception e) {
            throw new PapSdkException("Failed to record divergence row for " + entityType, e);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveCommitted(int transactionId) {
        repository.deletePendingByTransactionId(transactionId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveRolledBack(int transactionId, String errorReason, String errorMessage) {
        try {
            String errorJson = objectMapper.writeValueAsString(Map.of(
                    "reason", errorReason,
                    "message", errorMessage));
            repository.markRejectedByTransactionId(transactionId, errorJson);
        } catch (Exception e) {
            throw new PapSdkException("Failed to mark divergence rows REJECTED_DATA for tx " + transactionId, e);
        }
    }
}
