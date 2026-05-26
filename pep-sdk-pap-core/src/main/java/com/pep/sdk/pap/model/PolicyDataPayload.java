package com.pep.sdk.pap.model;

import java.time.Instant;

/**
 * Abstract base class that all typed entity payload classes extend.
 * Each payload class knows exactly what its PAP endpoint requires for each operation.
 */
public abstract class PolicyDataPayload {

    private final Instant occurredAt;

    protected PolicyDataPayload() {
        this.occurredAt = Instant.now();
    }

    protected PolicyDataPayload(Instant occurredAt) {
        this.occurredAt = occurredAt != null ? occurredAt : Instant.now();
    }

    /**
     * Gets the time at which the policy event occurred or was constructed.
     */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    /**
     * Produces a complete description of the HTTP call (PapRequest)
     * required to synchronize this payload's entity and event configuration.
     */
    public abstract PapRequest toPapRequest();
}
