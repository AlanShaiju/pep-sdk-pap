package com.example.pep.sdk.core.catalog;

import java.util.Map;

/**
 * Wire shape for one operation. All four maps key the wire-side name and value
 * the attribute source name. Empty maps are valid (e.g., a DELETE with no body).
 */
public record OperationSpec(
        String path,
        Map<String, String> pathVariables,
        Map<String, String> headers,
        Map<String, String> queryParams,
        Map<String, String> body
) { }
