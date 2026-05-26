package com.pep.sdk.pap.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PolicyDataPayloadTest {

    @Test
    void testSuccessfulBuilderAndPayloadCreation() {
        TestPolicyPayload payload = TestPolicyPayload.builder()
                .tenantId("tenant-123")
                .userId("user-456")
                .username("john_doe")
                .build();

        assertNotNull(payload.getOccurredAt());
        assertEquals("tenant-123", payload.getTenantId());
        assertEquals("user-456", payload.getUserId());
        assertEquals("john_doe", payload.getUsername());
    }

    @Test
    void testBuilderThrowsExceptionWhenRequiredFieldsAreMissing() {
        // Missing tenantId
        assertThrows(IllegalStateException.class, () -> {
            TestPolicyPayload.builder()
                    .userId("user-456")
                    .username("john_doe")
                    .build();
        });

        // Missing userId
        assertThrows(IllegalStateException.class, () -> {
            TestPolicyPayload.builder()
                    .tenantId("tenant-123")
                    .username("john_doe")
                    .build();
        });
    }

    @Test
    void testToPapRequestConversion() {
        TestPolicyPayload payload = TestPolicyPayload.builder()
                .tenantId("tenant-123")
                .userId("user-456")
                .username("john_doe")
                .build();

        PapRequest request = payload.toPapRequest();
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertEquals("/v1/tenants/{tenantId}/users", request.getPath());
        assertEquals("/v1/tenants/tenant-123/users", request.getResolvedPath());
        assertEquals(payload, request.getBody());
    }
}
