package com.pep.sdk.pap.exception;

/**
 * Exception thrown when client-side mapping, validation, or builder execution fails.
 */
public class PapClientException extends PapException {
    
    public PapClientException(String message) {
        super(message);
    }

    public PapClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
