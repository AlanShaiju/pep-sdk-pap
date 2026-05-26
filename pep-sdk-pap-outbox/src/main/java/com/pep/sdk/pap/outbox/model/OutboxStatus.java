package com.pep.sdk.pap.outbox.model;

/**
 * Lifecycle status of an outbox record.
 */
public enum OutboxStatus {
    /**
     * Record is pending delivery or scheduled for a retry attempt.
     */
    PENDING,

    /**
     * Delivery has failed continuously and exceeded the maximum retry count,
     * or encountered a terminal non-retryable exception. Requires manual intervention.
     */
    DEAD_LETTER,

    /**
     * The PAP explicitly rejected the payload structure (e.g., 400 Bad Request).
     * Re-attempts are skipped until corrected.
     */
    REJECTED_DATA
}
