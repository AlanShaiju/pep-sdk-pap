package com.example.pep.sdk.processor;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Read-only compile-time view of the catalog. Hand-rolled JSON parser to keep the
 * processor jar free of Jackson.
 *
 * <p>For each entity we extract the set of attribute source names referenced across
 * all four maps (pathVariables, headers, queryParams, body) of every operation.
 * The processor uses this set to validate that the entity covers each name with
 * either @PapAttribute on a field or @PapProperty on @PapEntity.
 */
final class CompileTimeCatalog {

    private final Map<String, EntityInfo> entities;

    /** All attribute source names this entity needs, across every declared operation. */
    record EntityInfo(Set<String> referencedSources) { }

    private CompileTimeCatalog(Map<String, EntityInfo> entities) { this.entities = entities; }

    boolean supports(String entity) { return entities.containsKey(entity); }
    Set<String> knownEntities()     { return entities.keySet(); }
    EntityInfo get(String entity)   { return entities.get(entity); }

    static CompileTimeCatalog load(Messager messager) {
        try (InputStream in = CompileTimeCatalog.class.getClassLoader()
                .getResourceAsStream("META-INF/pep-sdk/pap-endpoints.json")) {
            if (in == null) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "pap-endpoints.json not found on processor classpath");
                return new CompileTimeCatalog(Map.of());
            }
            return parse(read(in));
        } catch (Exception e) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Failed to load catalog: " + e.getMessage());
            return new CompileTimeCatalog(Map.of());
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * Parse the catalog. We're looking for:
     *   "EntityName": { "create": { "pathVariables": {...}, ... }, "update": {...}, ... }
     * For each operation's pathVariables/headers/queryParams/body we collect the VALUES
     * (which are attribute source names).
     */
    static CompileTimeCatalog parse(String json) {
        Map<String, EntityInfo> result = new HashMap<>();
        int i = json.indexOf('{');
        if (i < 0) return new CompileTimeCatalog(result);
        i++; // step past root opening brace

        while ((i = skipToString(json, i)) >= 0) {
            int next = parseOneEntity(json, i, result);
            if (next < 0) {
                break;
            }
            i = next;
        }
        return new CompileTimeCatalog(result);
    }

    /**
     * Parses a single {@code "EntityName": { ... }} pair starting at the opening quote index.
     * Adds the entity to {@code result} and returns the index just past its closing brace,
     * or -1 if the structure is malformed (caller stops).
     */
    private static int parseOneEntity(String json, int quoteStart, Map<String, EntityInfo> result) {
        int nameEnd = json.indexOf('"', quoteStart + 1);
        if (nameEnd < 0) {
            return -1;
        }
        int braceStart = json.indexOf('{', nameEnd);
        if (braceStart < 0) {
            return -1;
        }
        String entityName = json.substring(quoteStart + 1, nameEnd);
        int braceEnd = matchingBrace(json, braceStart);
        String body = json.substring(braceStart, braceEnd + 1);
        result.put(entityName, parseEntity(body));
        return braceEnd + 1;
    }

    private static EntityInfo parseEntity(String body) {
        Set<String> refs = new HashSet<>();
        for (String mapName : new String[]{"pathVariables", "headers", "queryParams", "body"}) {
            collectMapValues(body, mapName, refs);
        }
        return new EntityInfo(refs);
    }

    private static void collectMapValues(String body, String mapKey, Set<String> out) {
        int from = 0;
        while (true) {
            int idx = body.indexOf("\"" + mapKey + "\"", from);
            if (idx < 0) return;
            int mapStart = body.indexOf('{', idx);
            if (mapStart < 0) return;
            int mapEnd = matchingBrace(body, mapStart);
            String contents = body.substring(mapStart + 1, mapEnd);
            extractValues(contents, out);
            from = mapEnd + 1;
        }
    }

    /** Extract string values from "key": "value" pairs. */
    private static void extractValues(String contents, Set<String> out) {
        int i = 0;
        while (i < contents.length()) {
            int keyStart = contents.indexOf('"', i);
            if (keyStart < 0) return;
            int keyEnd = contents.indexOf('"', keyStart + 1);
            if (keyEnd < 0) return;
            int colon = contents.indexOf(':', keyEnd);
            if (colon < 0) return;
            int valStart = contents.indexOf('"', colon);
            if (valStart < 0) return;
            int valEnd = contents.indexOf('"', valStart + 1);
            if (valEnd < 0) return;
            String value = contents.substring(valStart + 1, valEnd);
            if (!value.isEmpty()) out.add(value);
            i = valEnd + 1;
        }
    }

    private static int skipToString(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') return i;
            if (c == '}') return -1;
        }
        return -1;
    }

    private static int matchingBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        throw new IllegalStateException("Unbalanced braces in catalog JSON");
    }
}
