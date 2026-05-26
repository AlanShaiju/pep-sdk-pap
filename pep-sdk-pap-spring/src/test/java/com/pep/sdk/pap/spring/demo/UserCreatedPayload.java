package com.pep.sdk.pap.spring.demo;

import com.pep.sdk.pap.model.PapEntity;
import com.pep.sdk.pap.model.PapEvent;
import com.pep.sdk.pap.model.PapRequest;
import com.pep.sdk.pap.model.PolicyDataPayload;
import com.pep.sdk.pap.registry.PapEndpointRegistry;

import java.time.Instant;

public class UserCreatedPayload extends PolicyDataPayload {

    private final String tenantId;
    private final String userId;
    private final String username;
    private final String email;

    private UserCreatedPayload(Builder builder) {
        super(builder.occurredAt);
        this.tenantId = builder.tenantId;
        this.userId = builder.userId;
        this.username = builder.username;
        this.email = builder.email;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public PapRequest toPapRequest() {
        PapEndpointRegistry.EndpointDefinition endpoint = PapEndpointRegistry.getEndpoint(PapEntity.USER, PapEvent.USER_CREATED);
        
        return PapRequest.builder()
                .method(endpoint.getMethod())
                .path(endpoint.getPathTemplate())
                .pathVariable("tenantId", tenantId)
                .body(this)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Instant occurredAt;
        private String tenantId;
        private String userId;
        private String username;
        private String email;

        public Builder occurredAt(Instant occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public UserCreatedPayload build() {
            if (tenantId == null || tenantId.trim().isEmpty()) {
                throw new IllegalStateException("tenantId is required");
            }
            if (userId == null || userId.trim().isEmpty()) {
                throw new IllegalStateException("userId is required");
            }
            return new UserCreatedPayload(this);
        }
    }
}
