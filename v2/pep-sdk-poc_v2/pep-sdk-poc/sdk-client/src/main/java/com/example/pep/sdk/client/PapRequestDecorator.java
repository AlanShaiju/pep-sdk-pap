package com.example.pep.sdk.client;

import com.example.pep.sdk.core.request.PapRequest;
import org.springframework.http.HttpHeaders;

/**
 * Pluggable decorator for outgoing requests (auth headers, tracing, etc.).
 * Auto-discovered as Spring beans; multiple decorators compose in declared order.
 */
@FunctionalInterface
public interface PapRequestDecorator {
    void decorate(HttpHeaders headers, PapRequest request);
}
