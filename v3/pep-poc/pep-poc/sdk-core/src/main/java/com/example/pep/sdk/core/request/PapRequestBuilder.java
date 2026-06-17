package com.example.pep.sdk.core.request;

import com.example.pep.sdk.core.catalog.EndpointSpec;
import com.example.pep.sdk.core.catalog.OperationSpec;
import com.example.pep.sdk.core.catalog.PapCatalog;
import com.example.pep.sdk.core.exception.PapSdkException;
import com.example.pep.sdk.core.model.HttpMethod;
import com.example.pep.sdk.core.model.Operation;
import com.example.pep.sdk.core.model.PapEntityChange;
import com.example.pep.sdk.core.model.PapEntityDescriptor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stateless. Walks the four catalog maps (pathVariables, headers, queryParams, body) and
 * sources each value from {@code change.sources()} — the precedence-resolved source map
 * built at capture time (@PapProperty > @PapAttribute > @PapInclude).
 */
public final class PapRequestBuilder {

    private final PapCatalog catalog;
    private final EndpointResolver resolver;

    public PapRequestBuilder(PapCatalog catalog, EndpointResolver resolver) {
        this.catalog = catalog;
        this.resolver = resolver;
    }

    public PapRequest build(PapEntityDescriptor descriptor, PapEntityChange change) {
        EndpointSpec endpoint = catalog.endpointFor(descriptor.papEntity());
        OperationSpec opSpec = endpoint.forOperation(change.operation());
        if (opSpec == null) {
            throw new PapSdkException("Catalog has no '" + change.operation() + "' for "
                    + descriptor.papEntity());
        }

        Map<String, Object> sources = change.sources();

        Map<String, String> pathVars  = resolveStrict(opSpec.pathVariables(), sources, descriptor, "pathVariables");
        Map<String, String> headers   = resolveStrict(opSpec.headers(),       sources, descriptor, "headers");
        Map<String, String> queryPars = resolveStrict(opSpec.queryParams(),   sources, descriptor, "queryParams");
        Map<String, Object> body      = resolveBody(opSpec.body(),            sources);

        String path = resolver.resolve(opSpec.path(), pathVars);
        HttpMethod method = descriptor.httpMethodFor(change.operation());

        return new PapRequest(
                method,
                path,
                change.operation() == Operation.DELETE ? Map.of() : body,
                headers,
                pathVars,
                queryPars,
                descriptor.papEntity(),
                change.entityId(),
                change.operation());
    }

    /** path/header/query sources are mandatory — missing or null fails the dispatch. */
    private static Map<String, String> resolveStrict(
            Map<String, String> mapping, Map<String, Object> sources,
            PapEntityDescriptor d, String mapName) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            Object value = sources.get(e.getValue());
            if (value == null) {
                throw new PapSdkException(
                        "No value for source '" + e.getValue() + "' (catalog " + mapName + "."
                                + e.getKey() + " on " + d.papEntity() + ")");
            }
            out.put(e.getKey(), String.valueOf(value));
        }
        return out;
    }

    /** body sources are optional — missing or null is silently omitted. */
    private static Map<String, Object> resolveBody(
            Map<String, String> mapping, Map<String, Object> sources) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            Object value = sources.get(e.getValue());
            if (value != null) out.put(e.getKey(), value);
        }
        return out;
    }
}
