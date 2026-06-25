package com.example.pep.sdk.sync;

import com.example.pep.sdk.client.PapClient;
import com.example.pep.sdk.core.annotation.CommunicationMode;
import com.example.pep.sdk.core.exception.PapException;
import com.example.pep.sdk.core.exception.PapSdkException;
import com.example.pep.sdk.core.model.PapEntityChange;
import com.example.pep.sdk.core.model.PapEntityDescriptor;
import com.example.pep.sdk.core.registry.PapEntityRegistry;
import com.example.pep.sdk.core.request.PapRequest;
import com.example.pep.sdk.core.request.PapRequestBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns TX2 (PAP invocation + audit recording) and TX3 (final status resolution) of the
 * three-transaction design. Both run REQUIRES_NEW: they commit independently of the business
 * transaction (TX1), whose fate is exactly what is still being decided.
 *
 * <h2>TX2 — {@link #invokeAndRecord}</h2>
 * Called from {@code beforeCommit} while TX1 is still open. In one new transaction it:
 * <ol>
 *   <li>generates a {@code transactionId} and inserts a PENDING STL_PEP_TRANSACTION row;</li>
 *   <li>walks the buffered changes in order, calling the PAP for each; on the first failure it
 *       stops calling the PAP and records every remaining change FAILED ("not attempted");</li>
 *   <li>batch-inserts one STL_PEP_DIVERGENCE row per change (SUCCESS or FAILED);</li>
 *   <li>on any failure, flips the transaction row to FAILED.</li>
 * </ol>
 * Crucially it <strong>returns</strong> a {@link PapInvocationResult} rather than throwing: a
 * REQUIRES_NEW method that threw would have its own inserts rolled back by Spring, destroying the
 * audit trail. Returning lets TX2 commit; the caller then throws (outside this transaction) to
 * roll TX1 back, by which point the audit rows are durable.
 *
 * <h2>TX3 — {@link #markSuccess} / {@link #markFailed}</h2>
 * Called from {@code afterCompletion}, when TX1's outcome is known and no transaction is active:
 * <ul>
 *   <li>{@link #markSuccess} (TX1 committed): PENDING → SUCCESS.</li>
 *   <li>{@link #markFailed} (TX1 rolled back although all PAP calls had succeeded — e.g. the
 *       commit itself failed): PENDING → FAILED. No-op if already FAILED (PAP-failure path,
 *       already resolved inside TX2).</li>
 * </ul>
 */
public class PapTransactionService {

    private static final Logger log = LoggerFactory.getLogger(PapTransactionService.class);

    private final PapEntityRegistry registry;
    private final PapRequestBuilder requestBuilder;
    private final PapClient papClient;
    private final PapTransactionRepository transactionRepository;
    private final PapDivergenceRepository divergenceRepository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PapTransactionService(PapEntityRegistry registry,
                                 PapRequestBuilder requestBuilder,
                                 PapClient papClient,
                                 PapTransactionRepository transactionRepository,
                                 PapDivergenceRepository divergenceRepository,
                                 EntityManager entityManager,
                                 ObjectMapper objectMapper,
                                 Clock clock) {
        this.registry = registry;
        this.requestBuilder = requestBuilder;
        this.papClient = papClient;
        this.transactionRepository = transactionRepository;
        this.divergenceRepository = divergenceRepository;
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ TX2

    /**
     * TX2. Performs every PAP call for the transaction and records the audit trail. Returns the
     * outcome; never throws on PAP failure (so this REQUIRES_NEW transaction commits the audit
     * rows). The caller turns a {@link PapInvocationResult#failed()} result into a TX1 rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PapInvocationResult invokeAndRecord(List<PapEntityChange> changes) {
        Instant now = clock.instant();
        int transactionId = nextTransactionId();
        PapTransaction txRow = transactionRepository.save(new PapTransaction(transactionId, now));

        List<PapDivergenceEntry> rows = new ArrayList<>(changes.size());
        boolean failed = false;
        String failureReason = null;

        for (PapEntityChange change : changes) {
            PapEntityDescriptor descriptor = registry.find(change.entityClass())
                    .orElseThrow(() -> new PapSdkException(
                            "No descriptor for " + change.entityClass().getName()));

            if (change.mode() != CommunicationMode.SYNC) {
                throw new PapSdkException("This POC is SYNC-only; entity " + descriptor.papEntity()
                        + " resolved mode " + change.mode() + " for " + change.operation());
            }

            PapRequest req = requestBuilder.build(descriptor, change);
            String payloadJson = json(fullRequestShape(req));

            if (failed) {
                // An earlier call already failed — never touch the PAP again; record for audit.
                rows.add(PapDivergenceEntry.failed(transactionId, descriptor.papEntity(),
                        change.operation().name(), payloadJson, now,
                        json(Map.of("reason", "NOT_ATTEMPTED",
                                "message", "Skipped because an earlier PAP call in this transaction failed."))));
                continue;
            }

            try {
                log.info("TX2 PAP dispatch: {} {} (entity={}, papEntity={})",
                        req.method(), req.path(), change.entityId(), descriptor.papEntity());
                String response = papClient.send(req); // throws PapException on 4xx/5xx
                rows.add(PapDivergenceEntry.success(transactionId, descriptor.papEntity(),
                        change.operation().name(), payloadJson, now,
                        response == null ? null : safeJsonOrWrap(response)));
            } catch (PapException e) {
                failed = true;
                failureReason = descriptor.papEntity() + " " + change.operation()
                        + " rejected/unavailable: " + e.getMessage();
                rows.add(PapDivergenceEntry.failed(transactionId, descriptor.papEntity(),
                        change.operation().name(), payloadJson, now,
                        json(Map.of("reason", "PAP_FAILURE", "message", e.getMessage()))));
            }
        }

        divergenceRepository.saveAll(rows);

        if (failed) {
            txRow.markFailed(failureReason, clock.instant());
            log.info("TX2 result: transaction {} FAILED ({})", transactionId, failureReason);
            return PapInvocationResult.failure(transactionId, failureReason);
        }
        log.info("TX2 result: transaction {} all PAP calls succeeded (PENDING)", transactionId);
        return PapInvocationResult.success(transactionId);
    }

    // ------------------------------------------------------------------ TX3

    /** TX3 (TX1 committed): PENDING → SUCCESS. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSuccess(int transactionId) {
        transactionRepository.findById(transactionId).ifPresent(t -> {
            if (PapTransaction.PENDING.equals(t.getStatus())) {
                t.markSuccess(clock.instant());
                log.info("TX3 result: transaction {} SUCCESS", transactionId);
            }
        });
    }

    /** TX3 (TX1 rolled back although all PAP calls had succeeded): PENDING → FAILED. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(int transactionId, String reason) {
        transactionRepository.findById(transactionId).ifPresent(t -> {
            if (PapTransaction.PENDING.equals(t.getStatus())) {
                t.markFailed(reason, clock.instant());
                log.info("TX3 result: transaction {} FAILED ({})", transactionId, reason);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private int nextTransactionId() {
        Number value = (Number) entityManager
                .createNativeQuery("SELECT nextval('pep_transaction_seq')")
                .getSingleResult();
        return value.intValue();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new PapSdkException("Failed to serialize JSON for divergence/transaction row", e);
        }
    }

    /** If the PAP returned a JSON body keep it as-is; otherwise wrap the raw text as a JSON string. */
    private String safeJsonOrWrap(String body) {
        String trimmed = body.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed;
        return json(Map.of("body", body));
    }

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
