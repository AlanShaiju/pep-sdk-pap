package com.pep.sdk.pap.registry;

import com.pep.sdk.pap.model.PapEntity;
import com.pep.sdk.pap.model.PapEvent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Registry mapping (Entity, Event) combinations to static target endpoints and HTTP methods.
 */
public final class PapEndpointRegistry {

    private static final Map<Key, EndpointDefinition> REGISTRY;

    static {
        Map<Key, EndpointDefinition> map = new HashMap<>();

        // Tenant mappings
        map.put(new Key(PapEntity.TENANT, PapEvent.TENANT_CREATED), new EndpointDefinition("POST", "/v1/tenants"));
        map.put(new Key(PapEntity.TENANT, PapEvent.TENANT_UPDATED), new EndpointDefinition("PUT", "/v1/tenants/{tenantId}"));
        map.put(new Key(PapEntity.TENANT, PapEvent.TENANT_DELETED), new EndpointDefinition("DELETE", "/v1/tenants/{tenantId}"));

        // User mappings
        map.put(new Key(PapEntity.USER, PapEvent.USER_CREATED), new EndpointDefinition("POST", "/v1/tenants/{tenantId}/users"));
        map.put(new Key(PapEntity.USER, PapEvent.USER_UPDATED), new EndpointDefinition("PUT", "/v1/tenants/{tenantId}/users/{userId}"));
        map.put(new Key(PapEntity.USER, PapEvent.USER_DELETED), new EndpointDefinition("DELETE", "/v1/tenants/{tenantId}/users/{userId}"));

        // Resource Type mappings
        map.put(new Key(PapEntity.RESOURCE_TYPE, PapEvent.RESOURCE_TYPE_CREATED), new EndpointDefinition("POST", "/v1/tenants/{tenantId}/resource-types"));
        map.put(new Key(PapEntity.RESOURCE_TYPE, PapEvent.RESOURCE_TYPE_UPDATED), new EndpointDefinition("PUT", "/v1/tenants/{tenantId}/resource-types/{typeId}"));
        map.put(new Key(PapEntity.RESOURCE_TYPE, PapEvent.RESOURCE_TYPE_DELETED), new EndpointDefinition("DELETE", "/v1/tenants/{tenantId}/resource-types/{typeId}"));

        // Resource Instance mappings
        map.put(new Key(PapEntity.RESOURCE_INSTANCE, PapEvent.RESOURCE_INSTANCE_CREATED), new EndpointDefinition("POST", "/v1/tenants/{tenantId}/resources"));
        map.put(new Key(PapEntity.RESOURCE_INSTANCE, PapEvent.RESOURCE_INSTANCE_UPDATED), new EndpointDefinition("PUT", "/v1/tenants/{tenantId}/resources/{resourceId}"));
        map.put(new Key(PapEntity.RESOURCE_INSTANCE, PapEvent.RESOURCE_INSTANCE_DELETED), new EndpointDefinition("DELETE", "/v1/tenants/{tenantId}/resources/{resourceId}"));

        // Topic mappings
        map.put(new Key(PapEntity.TOPIC, PapEvent.TOPIC_CREATED), new EndpointDefinition("POST", "/v1/tenants/{tenantId}/topics"));
        map.put(new Key(PapEntity.TOPIC, PapEvent.TOPIC_UPDATED), new EndpointDefinition("PUT", "/v1/tenants/{tenantId}/topics/{topicId}"));
        map.put(new Key(PapEntity.TOPIC, PapEvent.TOPIC_DELETED), new EndpointDefinition("DELETE", "/v1/tenants/{tenantId}/topics/{topicId}"));

        // Topic Access mappings
        map.put(new Key(PapEntity.TOPIC_ACCESS, PapEvent.TOPIC_ACCESS_CREATED), new EndpointDefinition("POST", "/v1/tenants/{tenantId}/topic-access"));
        map.put(new Key(PapEntity.TOPIC_ACCESS, PapEvent.TOPIC_ACCESS_UPDATED), new EndpointDefinition("PUT", "/v1/tenants/{tenantId}/topic-access/{accessId}"));
        map.put(new Key(PapEntity.TOPIC_ACCESS, PapEvent.TOPIC_ACCESS_DELETED), new EndpointDefinition("DELETE", "/v1/tenants/{tenantId}/topic-access/{accessId}"));

        // Role mappings
        map.put(new Key(PapEntity.ROLE, PapEvent.ROLE_CREATED), new EndpointDefinition("POST", "/v1/tenants/{tenantId}/roles"));
        map.put(new Key(PapEntity.ROLE, PapEvent.ROLE_UPDATED), new EndpointDefinition("PUT", "/v1/tenants/{tenantId}/roles/{roleId}"));
        map.put(new Key(PapEntity.ROLE, PapEvent.ROLE_DELETED), new EndpointDefinition("DELETE", "/v1/tenants/{tenantId}/roles/{roleId}"));

        // User Group mappings
        map.put(new Key(PapEntity.USER_GROUP_INSTANCE, PapEvent.USER_GROUP_INSTANCE_CREATED), new EndpointDefinition("POST", "/v1/tenants/{tenantId}/user-groups"));
        map.put(new Key(PapEntity.USER_GROUP_INSTANCE, PapEvent.USER_GROUP_INSTANCE_UPDATED), new EndpointDefinition("PUT", "/v1/tenants/{tenantId}/user-groups/{groupId}"));
        map.put(new Key(PapEntity.USER_GROUP_INSTANCE, PapEvent.USER_GROUP_INSTANCE_DELETED), new EndpointDefinition("DELETE", "/v1/tenants/{tenantId}/user-groups/{groupId}"));

        REGISTRY = Collections.unmodifiableMap(map);
    }

    private PapEndpointRegistry() {}

    /**
     * Retrieves the endpoint definition for a specific entity and event.
     */
    public static EndpointDefinition getEndpoint(PapEntity entity, PapEvent event) {
        EndpointDefinition def = REGISTRY.get(new Key(entity, event));
        if (def == null) {
            throw new IllegalArgumentException("No endpoint registered for entity " + entity + " and event " + event);
        }
        return def;
    }

    public static class EndpointDefinition {
        private final String method;
        private final String pathTemplate;

        public EndpointDefinition(String method, String pathTemplate) {
            this.method = method;
            this.pathTemplate = pathTemplate;
        }

        public String getMethod() {
            return method;
        }

        public String getPathTemplate() {
            return pathTemplate;
        }
    }

    private static final class Key {
        private final PapEntity entity;
        private final PapEvent event;

        public Key(PapEntity entity, PapEvent event) {
            this.entity = entity;
            this.event = event;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Key key = (Key) o;
            return entity == key.entity && event == key.event;
        }

        @Override
        public int hashCode() {
            return Objects.hash(entity, event);
        }
    }
}
