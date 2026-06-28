package com.example.pep.sdk.sync;

import com.example.pep.sdk.core.exception.PapTransactionRolledBackException;
import com.example.pep.sdk.core.model.PapEntityChange;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Orchestrates the three-transaction design across the business transaction's lifecycle.
 *
 * <h2>beforeCommit (TX1 still open)</h2>
 * Flushes pending SQL so the buffer is complete, drains the transaction-scoped {@link ChangeBuffer},
 * then calls TX2 ({@link PapTransactionService#invokeAndRecord}). TX2 commits its own audit trail and
 * returns a {@link PapInvocationResult}:
 * <ul>
 *   <li><b>success</b> — bind the {@code transactionId} for afterCompletion to finalize; TX1 goes on
 *       to commit normally.</li>
 *   <li><b>failure</b> — the audit trail is already FAILED + durable; throw
 *       {@link PapTransactionRolledBackException} to force TX1 to roll back. Nothing more to do in
 *       afterCompletion for this case (status already FAILED).</li>
 * </ul>
 *
 * <h2>afterCompletion (TX1 outcome known)</h2>
 * Runs TX3 only when a {@code transactionId} was bound (i.e. TX2 succeeded):
 * <ul>
 *   <li>COMMITTED — {@link PapTransactionService#markSuccess}: PENDING → SUCCESS.</li>
 *   <li>ROLLED_BACK / UNKNOWN — {@link PapTransactionService#markFailed}: PENDING → FAILED (TX1
 *       failed to commit even though every PAP call had succeeded — a real divergence).</li>
 * </ul>
 * Always clears the thread-bound buffer and id.
 */
public final class PapTransactionSynchronization implements TransactionSynchronization {

    private static final Logger log = LoggerFactory.getLogger(PapTransactionSynchronization.class);

    public static final String BUFFER_KEY = "com.example.pep.sdk.sync.BUFFER";
    private static final String TX_ID_KEY = "com.example.pep.sdk.sync.PEP_TX_ID";

    private final PapTransactionService transactionService;
    private final EntityManager entityManager;

    public PapTransactionSynchronization(PapTransactionService transactionService,
                                         EntityManager entityManager) {
        this.transactionService = transactionService;
        this.entityManager = entityManager;
    }

    @Override
    public void beforeCommit(boolean readOnly) {
        if (readOnly) return;

        // Force deferred UPDATE/DELETE SQL to run now, inside the still-open, still-rollback-able
        // TX1, so the listener has fired for every change and the buffer is complete. Does not commit.
        entityManager.flush();

        ChangeBuffer buffer = (ChangeBuffer) TransactionSynchronizationManager.getResource(BUFFER_KEY);
        if (buffer == null || buffer.isEmpty()) return;

        List<PapEntityChange> changes = List.copyOf(buffer.drain());

        // TX2: commits the audit trail (transaction + divergence rows) independently of TX1.
        PapInvocationResult result = transactionService.invokeAndRecord(changes);

        if (result.failed()) {
            // Audit trail already FAILED + durable. Force TX1 to roll back. Do NOT bind the id —
            // afterCompletion must not run TX3 for this path (status is already final).
            throw new PapTransactionRolledBackException(result.transactionId(), result.reason());
        }

        // All PAP calls succeeded; TX1 will now try to commit. Remember the id so afterCompletion
        // can finalize the transaction's status once TX1's outcome is known.
        TransactionSynchronizationManager.bindResource(TX_ID_KEY, result.transactionId());
    }

    @Override
    public void afterCompletion(int status) {
        Integer txId = (Integer) TransactionSynchronizationManager.getResource(TX_ID_KEY);
        if (txId != null) {
            // TX3.
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                transactionService.markSuccess(txId);
            } else {
                transactionService.markFailed(txId,
                        "Local transaction did not commit (status " + status
                                + ") although all PAP calls had succeeded.");
            }
            TransactionSynchronizationManager.unbindResource(TX_ID_KEY);
        }
        if (TransactionSynchronizationManager.hasResource(BUFFER_KEY)) {
            TransactionSynchronizationManager.unbindResource(BUFFER_KEY);
        }
    }
}
