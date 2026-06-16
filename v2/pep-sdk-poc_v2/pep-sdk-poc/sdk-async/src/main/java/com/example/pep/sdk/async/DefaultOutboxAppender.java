package com.example.pep.sdk.async;

import com.example.pep.sdk.core.exception.PapSdkException;
import com.example.pep.sdk.core.model.PapEntityChange;
import com.example.pep.sdk.core.model.PapEntityDescriptor;
import com.example.pep.sdk.core.request.PapRequest;
import com.example.pep.sdk.core.request.PapRequestBuilder;
import com.example.pep.sdk.sync.OutboxAppender;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;

public final class DefaultOutboxAppender implements OutboxAppender {

    private final PapOutboxRepository repository;
    private final PapRequestBuilder requestBuilder;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DefaultOutboxAppender(PapOutboxRepository repository,
                                 PapRequestBuilder requestBuilder,
                                 ObjectMapper objectMapper,
                                 Clock clock) {
        this.repository = repository;
        this.requestBuilder = requestBuilder;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void append(PapEntityChange change, PapEntityDescriptor descriptor) {
        PapRequest req = requestBuilder.build(descriptor, change);
        try {
            PapOutboxEntry row = new PapOutboxEntry(
                    descriptor.papEntity(),
                    change.operation(),
                    objectMapper.writeValueAsString(req.payload()),
                    objectMapper.writeValueAsString(req.headers()),
                    objectMapper.writeValueAsString(req.pathVariables()),
                    objectMapper.writeValueAsString(req.requestParams()),
                    clock.instant());
            repository.save(row);
        } catch (Exception e) {
            throw new PapSdkException("Failed to serialize outbox row for " + descriptor.papEntity(), e);
        }
    }
}
