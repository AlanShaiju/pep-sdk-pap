package com.pep.sdk.pap.spring.context;

import com.pep.sdk.pap.model.PapResponse;

/**
 * Thread-local holder for PAP response contexts, allowing services to inspect
 * responses after calling methods annotated with @NotifyPap (when propagateResponse is true).
 */
public class PapResponseHolder {

    private final ThreadLocal<PapResponse> context = new ThreadLocal<>();

    /**
     * Stores the given response in the current thread's context.
     */
    public void setResponse(PapResponse response) {
        context.set(response);
    }

    /**
     * Retrieves the response stored in the current thread's context.
     */
    public PapResponse getResponse() {
        return context.get();
    }

    /**
     * Clears the context to prevent thread-local leaks.
     */
    public void clear() {
        context.remove();
    }
}
