package com.pep.sdk.pap.parser;

import com.pep.sdk.pap.model.PapResponse;

import java.util.List;
import java.util.Map;

/**
 * Translates raw HTTP response parameters into a structured PapResponse.
 */
public final class PapResponseParser {

    private PapResponseParser() {}

    /**
     * Parses an HTTP call response parameters to generate a PapResponse.
     */
    public static PapResponse parse(int statusCode, String body, Map<String, List<String>> headers) {
        boolean success = (statusCode >= 200 && statusCode < 300);
        String errorMessage = null;

        if (!success) {
            errorMessage = "HTTP Error " + statusCode + ": " + (body != null ? body : "No response body available");
        }

        return PapResponse.builder()
                .statusCode(statusCode)
                .responseBody(body)
                .headers(headers)
                .success(success)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Creates a PapResponse corresponding to a network transport error or timeout.
     */
    public static PapResponse parseFailure(Exception exception) {
        return PapResponse.builder()
                .statusCode(503)
                .success(false)
                .errorMessage(exception.getMessage() != null ? exception.getMessage() : exception.toString())
                .build();
    }
}
