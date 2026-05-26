package com.pep.sdk.pap.model;

/**
 * Defines the fallback strategies when synchronization to the PAP fails.
 */
public enum FailBehavior {
    /**
     * Do nothing extra immediately; the outbox record is transactionally committed
     * and delivered asynchronously when the PAP recovers. The service is unaffected.
     */
    FALLBACK_TO_OUTBOX,

    /**
     * Call the specified compensation method in the same bean, on a new transaction,
     * to rollback the domain state and maintain consistency.
     */
    COMPENSATE,

    /**
     * Log the failure with full context, increment metrics, and return execution normally.
     */
    LOG_AND_CONTINUE
}
