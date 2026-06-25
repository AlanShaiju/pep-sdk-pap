package com.example.pep.sdk.starter;

import com.example.pep.sdk.core.exception.PapUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

final class Resilience4jFactory {

    private Resilience4jFactory() { }

    static Retry buildRetry(PapSdkProperties.Retry p) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(p.getMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        p.getInitialBackoff(), 2.0, 0.2, p.getMaxBackoff()))
                .retryOnException(t -> t instanceof PapUnavailableException)
                .build();
        return Retry.of("pap-sdk", config);
    }

    static CircuitBreaker buildCircuitBreaker(PapSdkProperties.CircuitBreaker p) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(p.getFailureRateThreshold())
                .slidingWindowSize(p.getSlidingWindowSize())
                .waitDurationInOpenState(p.getWaitDurationInOpenState())
                .recordException(t -> t instanceof PapUnavailableException)
                .build();
        return CircuitBreaker.of("pap-sdk", config);
    }
}
