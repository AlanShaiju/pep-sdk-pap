package com.pep.sdk.pap.registry;

import com.pep.sdk.pap.model.PapEntity;
import com.pep.sdk.pap.model.PapEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PapEndpointRegistryTest {

    @Test
    void testGetEndpointSuccess() {
        PapEndpointRegistry.EndpointDefinition def = PapEndpointRegistry.getEndpoint(PapEntity.USER, PapEvent.USER_CREATED);
        assertNotNull(def);
        assertEquals("POST", def.getMethod());
        assertEquals("/v1/tenants/{tenantId}/users", def.getPathTemplate());

        PapEndpointRegistry.EndpointDefinition tenantDef = PapEndpointRegistry.getEndpoint(PapEntity.TENANT, PapEvent.TENANT_DELETED);
        assertNotNull(tenantDef);
        assertEquals("DELETE", tenantDef.getMethod());
        assertEquals("/v1/tenants/{tenantId}", tenantDef.getPathTemplate());
    }

    @Test
    void testGetEndpointThrowsExceptionForUnmapped() {
        // Look up unmapped entity-event combination
        assertThrows(IllegalArgumentException.class, () -> {
            PapEndpointRegistry.getEndpoint(PapEntity.USER, PapEvent.TENANT_CREATED);
        });
    }
}
