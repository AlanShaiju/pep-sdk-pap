package com.example.pep.sdk.sync;

import com.example.pep.sdk.core.exception.PapSdkException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

/**
 * Writes and resolves STL_PEP_DIVERGENCE rows. Every method is REQUIRES_NEW:
 *
 * <ul>
 *   <li>{@link #nextTransactionId()} / {@link #recordPending} run while the business
 *       transaction is mid-beforeCommit — they must commit independently of it, since that
 *       transaction's fate is exactly what is still unknown.</li>
 *   <li>{@link #resolveCommitted} / {@link #resolveRolledBack} run from afterCompletion, which
 *       has no active transaction; REQUIRES_NEW opens one.</li>
 * </ul>
 */
public class DivergenceRecorder {

    private final PapDivergenceRepository repository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DivergenceRecorder(PapDivergenceRepository repository,
                              EntityManager entityManager,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** One value per beforeCommit() invocation, shared by every row from that transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int nextTransactionId() {
        Number value = (Number) entityManager
                .createNativeQuery("SELECT nextval('pap_divergence_transaction_seq')")
                .getSingleResult();
        return value.intValue();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPending(int transactionId, String entityType, String operation, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            repository.save(new PapDivergenceEntry(transactionId, entityType, operation, json, clock.instant()));
        } catch (Exception e) {
            throw new PapSdkException("Failed to record divergence row for " + entityType, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveCommitted(int transactionId) {
        repository.deletePendingByTransactionId(transactionId);
    }

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
