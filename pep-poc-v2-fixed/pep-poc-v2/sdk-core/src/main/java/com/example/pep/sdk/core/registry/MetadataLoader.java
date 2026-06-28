package com.example.pep.sdk.core.registry;

import com.example.pep.sdk.core.annotation.CommunicationMode;
import com.example.pep.sdk.core.exception.PapSdkException;
import com.example.pep.sdk.core.model.AttributeAccessor;
import com.example.pep.sdk.core.model.IncludeAccessor;
import com.example.pep.sdk.core.model.Operation;
import com.example.pep.sdk.core.model.PapEntityDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads every META-INF/pep-sdk/metadata.json on the classpath (one per module) and
 * constructs the registry. Duplicate entity classes across files fail startup.
 *
 * <p>Include dot-paths are resolved to Field[] eagerly here — a path that doesn't
 * resolve fails startup with PapSdkException (belt-and-suspenders behind the
 * compile-time check).
 */
public final class MetadataLoader {

    private static final Logger log = LoggerFactory.getLogger(MetadataLoader.class);
    private static final String METADATA_RESOURCE = "META-INF/pep-sdk/metadata.json";

    private final CommunicationMode globalDefaultMode;

    public MetadataLoader(CommunicationMode globalDefaultMode) {
        this.globalDefaultMode = globalDefaultMode;
    }

    public PapEntityRegistry load(ClassLoader classLoader) {
        Map<Class<?>, PapEntityDescriptor> result = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        int filesRead = 0;

        try {
            Enumeration<URL> urls = classLoader.getResources(METADATA_RESOURCE);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                filesRead++;
                try (InputStream in = url.openStream()) {
                    JsonNode root = mapper.readTree(in);
                    for (JsonNode e : root.path("entities")) {
                        PapEntityDescriptor d = parse(e, classLoader);
                        if (result.put(d.entityClass(), d) != null) {
                            throw new PapSdkException(
                                    "Duplicate @PapEntity across metadata files: " + d.entityClass().getName());
                        }
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new PapSdkException("Failed to load metadata", e);
        }

        log.info("PEP SDK : loaded {} metadata file(s), {} entity descriptors", filesRead, result.size());
        result.values().forEach(d -> log.info("  {} -> {} [C:{} U:{} D:{}]",
                d.entityClass().getSimpleName(), d.papEntity(),
                d.modeFor(Operation.CREATE), d.modeFor(Operation.UPDATE), d.modeFor(Operation.DELETE)));
        return new PapEntityRegistry(result);
    }

    private PapEntityDescriptor parse(JsonNode node, ClassLoader cl) {
        String className = node.path("entityClass").asText();
        Class<?> cls;
        try {
            cls = Class.forName(className, false, cl);
        } catch (ClassNotFoundException e) {
            throw new PapSdkException("Entity class not found at runtime: " + className, e);
        }

        String papEntity = node.path("papEntity").asText();
        String idFieldName = node.path("idField").asText();

        Map<String, String> properties = new LinkedHashMap<>();
        node.path("properties").fields().forEachRemaining(p -> properties.put(p.getKey(), p.getValue().asText()));

        Map<Operation, CommunicationMode> opModes = new LinkedHashMap<>();
        node.path("operationModes").fields().forEachRemaining(om -> opModes.put(
                Operation.valueOf(om.getKey()),
                CommunicationMode.valueOf(om.getValue().asText())));

        List<AttributeAccessor> attrs = new ArrayList<>();
        for (JsonNode a : node.path("attributes")) {
            String fieldName = a.path("fieldName").asText();
            attrs.add(new AttributeAccessor(
                    a.path("attributeName").asText(),
                    lookupField(cls, fieldName),
                    fieldName.equals(idFieldName)));
        }

        List<IncludeAccessor> includes = new ArrayList<>();
        for (JsonNode inc : node.path("includes")) {
            includes.add(buildInclude(cls,
                    inc.path("fieldName").asText(),
                    inc.path("name").asText(),
                    inc.path("attribute").asText()));
        }

        return new PapEntityDescriptor(cls, papEntity, properties, opModes, attrs, includes, globalDefaultMode);
    }

    /**
     * Resolve the include's Field chain: the relationship field on the owner, then one Field
     * per dot-path segment. Any unresolvable segment fails startup.
     */
    private static IncludeAccessor buildInclude(Class<?> owner, String relationField,
                                                String name, String attributePath) {
        List<Field> chain = new ArrayList<>();
        Field rel = lookupField(owner, relationField);
        chain.add(rel);

        Class<?> current = rel.getType();
        for (String segment : attributePath.split("\\.")) {
            Field f = lookupField(current, segment);
            chain.add(f);
            current = f.getType();
        }
        return new IncludeAccessor(name, chain.toArray(Field[]::new));
    }

    private static Field lookupField(Class<?> cls, String fieldName) {
        Class<?> c = cls;
        while (c != null) {
            try { return c.getDeclaredField(fieldName); }
            catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
        }
        throw new PapSdkException("Field '" + fieldName + "' not found on " + cls.getName()
                + " (referenced by @PapInclude or metadata)");
    }
}
