package com.example.pep.sdk.core.request;

import com.example.pep.sdk.core.model.HttpMethod;
import com.example.pep.sdk.core.model.Operation;

import java.util.Map;

public record PapRequest(
        HttpMethod method,
        String path,
        Map<String, Object> payload,
        Map<String, String> headers,
        Map<String, String> pathVariables,
        Map<String, String> requestParams,
        String entityType,
        String entityId,
        Operation operation
) { }
