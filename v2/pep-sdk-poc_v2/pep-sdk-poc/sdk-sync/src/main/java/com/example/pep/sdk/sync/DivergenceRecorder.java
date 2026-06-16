package com.example.pep.sdk.sync;

/**
 * SPI for recording SYNC dual-write divergence (PAP write succeeded, local transaction's
 * outcome not yet known / ultimately rolled back). Implemented in sdk-async alongside the
 * outbox, for the same reason OutboxAppender is an SPI here: sdk-sync stays JPA-free.
 *
 * <p>Lifecycle, one {@code transactionId} per {@code beforeCommit()} invocation:
 * <ol>
 *   <li>{@link #nextTransactionId()} once, before dispatching any change.</li>
 *   <li>{@link #recordPending} once per successful PAP dispatch within that transaction.
 *       Must commit independently of the caller's transaction (the caller's transaction
 *       may roll back after this returns).</li>
 *   <li>Exactly one of {@link #resolveCommitted} / {@link #resolveRolledBack}, called from
 *       {@code afterCompletion}, using the same {@code transactionId} to resolve every row
 *       written in step 2.</li>
 * </ol>
 */
public interface DivergenceRecorder {

    int nextTransactionId();

    void recordPending(int transactionId, String entityType, String operation, java.util.Map<String, Object> payload);

    /** Local transaction committed — every PENDING row for this id was a false alarm. */
    void resolveCommitted(int transactionId);

    /** Local transaction rolled back — every PENDING row for this id is now confirmed stale at the PAP. */
    void resolveRolledBack(int transactionId, String errorReason, String errorMessage);
}
