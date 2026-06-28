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
import java.lang.reflect.Method;
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
 * <p>Include dot-paths are resolved to a chain of public getter {@link Method}s eagerly
 * here — a path that doesn't resolve to a getter fails startup with PapSdkException
 * (belt-and-suspenders behind the compile-time check). Using getters instead of direct
 * field access keeps the SDK free of {@code Field.setAccessible(true)} and respects
 * entity encapsulation.
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
                    lookupGetter(cls, fieldName),
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
     * Resolve the include's getter chain: the relationship getter on the owner, then one
     * getter per dot-path segment. The walk follows getter return types. Any unresolvable
     * segment fails startup.
     */
    private static IncludeAccessor buildInclude(Class<?> owner, String relationField,
                                                String name, String attributePath) {
        List<Method> chain = new ArrayList<>();
        Method rel = lookupGetter(owner, relationField);
        chain.add(rel);

        Class<?> current = rel.getReturnType();
        for (String segment : attributePath.split("\\.")) {
            Method getter = lookupGetter(current, segment);
            chain.add(getter);
            current = getter.getReturnType();
        }
        return new IncludeAccessor(name, chain.toArray(Method[]::new));
    }

    /**
     * Resolve the public JavaBean getter for a field. For field {@code code} this looks for
     * {@code getCode()}; for a {@code boolean} field {@code active} it also tries {@code isActive()}.
     * Walks up the superclass hierarchy. Fails startup if no matching public getter exists.
     */
    private static Method lookupGetter(Class<?> cls, String fieldName) {
        String suffix = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        Method getter = findMethod(cls, "get" + suffix);
        if (getter == null) {
            getter = findMethod(cls, "is" + suffix);
        }
        if (getter == null) {
            throw new PapSdkException("No public getter for field '" + fieldName + "' on "
                    + cls.getName() + " (expected get" + suffix + "() or is" + suffix
                    + "()); referenced by @PapAttribute/@PapInclude or metadata");
        }
        return getter;
    }

    private static Method findMethod(Class<?> cls, String methodName) {
        try {
            return cls.getMethod(methodName);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
