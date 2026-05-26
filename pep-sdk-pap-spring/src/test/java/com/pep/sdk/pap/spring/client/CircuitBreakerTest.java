package com.pep.sdk.pap.spring.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    @Test
    void testCircuitBreakerTransitions() throws InterruptedException {
        // threshold 3 failures, reset after 100ms
        CircuitBreaker cb = new CircuitBreaker(3, 100);

        // Initially CLOSED
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.isCallAllowed());

        // First failure
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        // Second failure
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        // Third failure -> Transitions to OPEN
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.isCallAllowed());

        // Wait for reset timeout
        Thread.sleep(150);

        // State changes to HALF_OPEN
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.getState());
        assertTrue(cb.isCallAllowed());

        // Success closes circuit
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.isCallAllowed());
    }
}
