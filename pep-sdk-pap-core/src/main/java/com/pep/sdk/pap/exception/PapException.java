package com.pep.sdk.pap.exception;

import com.pep.sdk.pap.model.PapResponse;

/**
 * Base exception for PEP SDK errors.
 */
public class PapException extends RuntimeException {
    public PapException(String message) {
        super(message);
    }

    public PapException(String message, Throwable cause) {
        super(message, cause);
    }
}
