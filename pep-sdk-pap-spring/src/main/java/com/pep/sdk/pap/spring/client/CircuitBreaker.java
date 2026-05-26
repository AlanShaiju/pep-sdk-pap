package com.pep.sdk.pap.spring.client;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight Circuit Breaker for tracking connectivity failure rates to the PAP.
 */
public class CircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int failureThreshold;
    private final long resetTimeoutMs;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile long lastStateChangeTimestamp = System.currentTimeMillis();

    public CircuitBreaker(int failureThreshold, long resetTimeoutMs) {
        this.failureThreshold = failureThreshold;
        this.resetTimeoutMs = resetTimeoutMs;
    }

    public State getState() {
        checkResetTimeout();
        return state.get();
    }

    public boolean isCallAllowed() {
        State current = getState();
        return current == State.CLOSED || current == State.HALF_OPEN;
    }

    public void recordSuccess() {
        if (state.get() == State.HALF_OPEN) {
            transitionTo(State.CLOSED);
        }
        failureCount.set(0);
    }

    public void recordFailure() {
        int failures = failureCount.incrementAndGet();
        if (failures >= failureThreshold && state.get() == State.CLOSED) {
            transitionTo(State.OPEN);
        }
    }

    private void checkResetTimeout() {
        if (state.get() == State.OPEN) {
            long elapsed = System.currentTimeMillis() - lastStateChangeTimestamp;
            if (elapsed >= resetTimeoutMs) {
                transitionTo(State.HALF_OPEN);
            }
        }
    }

    private void transitionTo(State newState) {
        if (state.getAndSet(newState) != newState) {
            lastStateChangeTimestamp = System.currentTimeMillis();
            if (newState == State.CLOSED) {
                failureCount.set(0);
            }
        }
    }
}
