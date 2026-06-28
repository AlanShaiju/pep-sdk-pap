package com.example.pep.sdk.core.exception;

public class PapRejectedException extends PapException {
    public PapRejectedException(int statusCode, String reason) {
        super("PAP rejected: " + statusCode + " " + reason);
    }
}
