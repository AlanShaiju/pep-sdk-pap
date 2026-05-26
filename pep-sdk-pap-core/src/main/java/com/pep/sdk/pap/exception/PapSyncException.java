package com.pep.sdk.pap.exception;

import com.pep.sdk.pap.model.PapResponse;

/**
 * Exception thrown when a synchronization call to the PAP fails.
 */
public class PapSyncException extends PapException {
    
    private final PapResponse response;

    public PapSyncException(String message, PapResponse response) {
        super(message);
        this.response = response;
    }

    public PapSyncException(String message, PapResponse response, Throwable cause) {
        super(message, cause);
        this.response = response;
    }

    public PapResponse getResponse() {
        return response;
    }
}
