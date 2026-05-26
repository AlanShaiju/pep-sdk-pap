package com.pep.sdk.pap.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured result of a PAP HTTP call.
 */
public class PapResponse {

    private final int statusCode;
    private final String responseBody;
    private final Map<String, List<String>> headers;
    private final boolean success;
    private final String errorMessage;

    private PapResponse(Builder builder) {
        this.statusCode = builder.statusCode;
        this.responseBody = builder.responseBody;
        this.headers = Collections.unmodifiableMap(new HashMap<>(builder.headers));
        this.success = builder.success;
        this.errorMessage = builder.errorMessage;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int statusCode;
        private String responseBody;
        private final Map<String, List<String>> headers = new HashMap<>();
        private boolean success;
        private String errorMessage;

        public Builder statusCode(int statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        public Builder responseBody(String responseBody) {
            this.responseBody = responseBody;
            return this;
        }

        public Builder headers(Map<String, List<String>> headers) {
            if (headers != null) {
                this.headers.putAll(headers);
            }
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public PapResponse build() {
            return new PapResponse(this);
        }
    }
}
