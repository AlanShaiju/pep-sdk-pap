package com.example.pep.sdk.core.exception;

public class PapRejectedException extends PapException {
    private final int statusCode;
    private final String reason;

    public PapRejectedException(int statusCode, String reason) {
        super("PAP rejected: " + statusCode + " " + reason);
        this.statusCode = statusCode;
        this.reason = reason;
    }
    public int statusCode() { return statusCode; }
    public String reason()  { return reason; }
}
