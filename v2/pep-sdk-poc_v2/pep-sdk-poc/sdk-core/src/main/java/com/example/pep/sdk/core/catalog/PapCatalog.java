package com.example.pep.sdk.core.catalog;

import com.example.pep.sdk.core.exception.PapSdkException;
import com.example.pep.sdk.core.model.Operation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Runtime view of META-INF/pep-sdk/pap-endpoints.json. Loaded once at startup.
 *
 * Catalog shape (per the design):
 *   {
 *     "ResourceInstance": {
 *       "create": { "path": "...", "pathVariables": {...}, "headers": {...},
 *                   "queryParams": {...}, "body": {...} },
 *       "update": { ... },
 *       "delete": { ... }
 *     }
 *   }
 */
public final class PapCatalog {

    private static final Logger log = LoggerFactory.getLogger(PapCatalog.class);
    private static final String CATALOG_RESOURCE = "META-INF/pep-sdk/pap-endpoints.json";

    private final Map<String, EndpointSpec> entities;

    public PapCatalog() { this(Thread.currentThread().getContextClassLoader()); }

    public PapCatalog(ClassLoader cl) {
        this.entities = load(cl);
        log.info("PEP SDK : loaded catalog with {} entities: {}", entities.size(), entities.keySet());
    }

    private static Map<String, EndpointSpec> load(ClassLoader cl) {
        try (InputStream in = cl.getResourceAsStream(CATALOG_RESOURCE)) {
            if (in == null) throw new PapSdkException("Catalog not found: " + CATALOG_RESOURCE);
            JsonNode root = new ObjectMapper().readTree(in);
            Map<String, EndpointSpec> result = new LinkedHashMap<>();
            root.fields().forEachRemaining(e -> result.put(e.getKey(), parseEntity(e.getValue())));
            return Map.copyOf(result);
        } catch (IOException e) {
            throw new PapSdkException("Failed to read catalog", e);
        }
    }

    private static EndpointSpec parseEntity(JsonNode node) {
        Map<Operation, OperationSpec> ops = new EnumMap<>(Operation.class);
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            Operation op = switch (e.getKey().toLowerCase()) {
                case "create" -> Operation.CREATE;
                case "update" -> Operation.UPDATE;
                case "delete" -> Operation.DELETE;
                default -> null;
            };
            if (op == null) continue;
            ops.put(op, parseOperation(e.getValue()));
        }
        return new EndpointSpec(Map.copyOf(ops));
    }

    private static OperationSpec parseOperation(JsonNode node) {
        return new OperationSpec(
                node.path("path").asText(),
                toMap(node.path("pathVariables")),
                toMap(node.path("headers")),
                toMap(node.path("queryParams")),
                toMap(node.path("body")));
    }

    private static Map<String, String> toMap(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isObject()) return Map.of();
        Map<String, String> m = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
        return Map.copyOf(m);
    }

    public Set<String> knownEntities()       { return entities.keySet(); }
    public boolean supports(String name)     { return entities.containsKey(name); }
    public EndpointSpec endpointFor(String n) {
        EndpointSpec s = entities.get(n);
        if (s == null) throw new PapSdkException("Unknown PAP entity: " + n);
        return s;
    }
}
