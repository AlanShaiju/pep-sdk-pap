package com.pep.sdk.pap.model;

/**
 * Configuration detailing the timing and count of retries for failed PAP HTTP calls.
 */
public class RetryPolicy {

    private final int maxAttempts;
    private final long backoffDelayMs;
    private final double backoffMultiplier;

    public RetryPolicy(int maxAttempts, long backoffDelayMs, double backoffMultiplier) {
        this.maxAttempts = maxAttempts;
        this.backoffDelayMs = backoffDelayMs;
        this.backoffMultiplier = backoffMultiplier;
    }

    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(3, 1000, 2.0);
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getBackoffDelayMs() {
        return backoffDelayMs;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }
}
