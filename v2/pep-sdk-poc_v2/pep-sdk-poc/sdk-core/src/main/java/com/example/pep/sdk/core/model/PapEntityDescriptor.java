package com.example.pep.sdk.core.model;

import com.example.pep.sdk.core.annotation.CommunicationMode;
import com.example.pep.sdk.core.exception.PapSdkException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-@PapEntity runtime metadata. Built once at startup by MetadataLoader; immutable.
 *
 * <p>{@link #resolveSources(Object)} builds the source map with the v9 precedence:
 * {@code @PapProperty > @PapAttribute > @PapInclude}. Implemented by writing lowest
 * precedence first and letting each successive layer overwrite.
 */
public final class PapEntityDescriptor {

    private static final Logger log = LoggerFactory.getLogger(PapEntityDescriptor.class);

    private final Class<?> entityClass;
    private final String papEntity;
    private final Map<String, String> properties;
    private final Map<Operation, CommunicationMode> operationModes;
    private final List<AttributeAccessor> directAttributes;
    private final List<IncludeAccessor> includes;
    private final AttributeAccessor idAttribute;
    private final CommunicationMode globalDefaultMode;
    private final Map<Operation, HttpMethod> httpMethods;

    public PapEntityDescriptor(
            Class<?> entityClass,
            String papEntity,
            Map<String, String> properties,
            Map<Operation, CommunicationMode> operationModes,
            List<AttributeAccessor> directAttributes,
            List<IncludeAccessor> includes,
            CommunicationMode globalDefaultMode) {

        this.entityClass = entityClass;
        this.papEntity = papEntity;
        this.properties = Map.copyOf(properties);
        this.operationModes = Map.copyOf(operationModes);
        this.directAttributes = List.copyOf(directAttributes);
        this.includes = List.copyOf(includes);
        this.globalDefaultMode = globalDefaultMode;
        this.httpMethods = Map.of(
                Operation.CREATE, HttpMethod.POST,
                Operation.UPDATE, HttpMethod.PATCH,
                Operation.DELETE, HttpMethod.DELETE);

        this.idAttribute = directAttributes.stream()
                .filter(AttributeAccessor::isIdAttribute)
                .findFirst()
                .orElseThrow(() -> new PapSdkException(
                        "No @Id field with @PapAttribute on " + entityClass.getName()));
    }

    public Class<?> entityClass()                 { return entityClass; }
    public String papEntity()                     { return papEntity; }
    public Map<String, String> properties()       { return properties; }
    public AttributeAccessor idAttribute()        { return idAttribute; }
    public HttpMethod httpMethodFor(Operation op) { return httpMethods.get(op); }

    /** Per-operation declared mode wins; otherwise global default; otherwise SYNC. */
    public CommunicationMode modeFor(Operation op) {
        CommunicationMode declared = operationModes.get(op);
        if (declared != null) return declared;
        return globalDefaultMode != null ? globalDefaultMode : CommunicationMode.SYNC;
    }

    /**
     * Builds the source map by precedence: includes (lowest) first, then direct attributes,
     * then properties (highest) — each successive put overwrites, so highest wins.
     */
    public Map<String, Object> resolveSources(Object entity) {
        Map<String, Object> out = new LinkedHashMap<>();

        for (IncludeAccessor inc : includes) {
            Object value = inc.evaluate(entity);
            if (value != null) out.put(inc.name(), value);
        }

        for (AttributeAccessor a : directAttributes) {
            Object value = a.read(entity);
            if (out.containsKey(a.attributeName()) && log.isDebugEnabled()) {
                log.debug("source '{}' from @PapAttribute shadows @PapInclude on {}",
                        a.attributeName(), entityClass.getSimpleName());
            }
            out.put(a.attributeName(), value);
        }

        for (Map.Entry<String, String> p : properties.entrySet()) {
            if (out.containsKey(p.getKey()) && log.isDebugEnabled()) {
                log.debug("source '{}' from @PapProperty shadows entity-derived value on {}",
                        p.getKey(), entityClass.getSimpleName());
            }
            out.put(p.getKey(), p.getValue());
        }

        return out;
    }

    public String readId(Object entity) {
        Object v = idAttribute.read(entity);
        return v == null ? null : v.toString();
    }
}
