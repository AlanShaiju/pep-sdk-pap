package com.example.pep.sdk.core.model;

import com.example.pep.sdk.core.annotation.CommunicationMode;

import java.util.Map;

/**
 * One captured entity change. {@code sources} is the fully-resolved source map
 * (precedence @PapProperty > @PapAttribute > @PapInclude already applied at capture time).
 */
public record PapEntityChange(
        Class<?> entityClass,
        String entityId,
        Operation operation,
        CommunicationMode mode,
        Map<String, Object> sources
) { }
