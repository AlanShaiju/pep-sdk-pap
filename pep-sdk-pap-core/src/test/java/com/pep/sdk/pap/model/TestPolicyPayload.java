package com.pep.sdk.pap.model;

import com.pep.sdk.pap.registry.PapEndpointRegistry;
import java.time.Instant;

public class TestPolicyPayload extends PolicyDataPayload {

    private final String tenantId;
    private final String userId;
    private final String username;

    private TestPolicyPayload(Builder builder) {
        super(builder.occurredAt);
        this.tenantId = builder.tenantId;
        this.userId = builder.userId;
        this.username = builder.username;
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

    @Override
    public PapRequest toPapRequest() {
        PapEndpointRegistry.EndpointDefinition def = PapEndpointRegistry.getEndpoint(PapEntity.USER, PapEvent.USER_CREATED);
        return PapRequest.builder()
                .method(def.getMethod())
                .path(def.getPathTemplate())
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

        public TestPolicyPayload build() {
            if (tenantId == null || tenantId.trim().isEmpty()) {
                throw new IllegalStateException("tenantId is required");
            }
            if (userId == null || userId.trim().isEmpty()) {
                throw new IllegalStateException("userId is required");
            }
            return new TestPolicyPayload(this);
        }
    }
}
