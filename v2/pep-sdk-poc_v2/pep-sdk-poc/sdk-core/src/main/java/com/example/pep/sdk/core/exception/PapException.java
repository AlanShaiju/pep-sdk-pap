package com.example.pep.sdk.core.exception;

public abstract class PapException extends RuntimeException {
    protected PapException(String message) { super(message); }
    protected PapException(String message, Throwable cause) { super(message, cause); }
}
