package com.pep.sdk.pap.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Descriptor of one HTTP call to the PAP.
 */
public class PapRequest {

    private final String method;
    private final String path;
    private final Map<String, String> pathVariables;
    private final Map<String, String> queryParameters;
    private final Map<String, String> headers;
    private final Object body;

    private PapRequest(Builder builder) {
        this.method = builder.method;
        this.path = builder.path;
        this.pathVariables = Collections.unmodifiableMap(new HashMap<>(builder.pathVariables));
        this.queryParameters = Collections.unmodifiableMap(new HashMap<>(builder.queryParameters));
        this.headers = Collections.unmodifiableMap(new HashMap<>(builder.headers));
        this.body = builder.body;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public Map<String, String> getPathVariables() {
        return pathVariables;
    }

    public Map<String, String> getQueryParameters() {
        return queryParameters;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Object getBody() {
        return body;
    }

    public String getResolvedPath() {
        String resolved = path;
        for (Map.Entry<String, String> entry : pathVariables.entrySet()) {
            resolved = resolved.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return resolved;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String method;
        private String path;
        private final Map<String, String> pathVariables = new HashMap<>();
        private final Map<String, String> queryParameters = new HashMap<>();
        private final Map<String, String> headers = new HashMap<>();
        private Object body;

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder pathVariable(String key, String value) {
            if (value != null) {
                this.pathVariables.put(key, value);
            }
            return this;
        }

        public Builder queryParam(String key, String value) {
            if (value != null) {
                this.queryParameters.put(key, value);
            }
            return this;
        }

        public Builder header(String key, String value) {
            if (value != null) {
                this.headers.put(key, value);
            }
            return this;
        }

        public Builder body(Object body) {
            this.body = body;
            return this;
        }

        public PapRequest build() {
            if (method == null || method.trim().isEmpty()) {
                throw new IllegalStateException("HTTP method is required");
            }
            if (path == null || path.trim().isEmpty()) {
                throw new IllegalStateException("HTTP path is required");
            }
            return new PapRequest(this);
        }
    }
}
