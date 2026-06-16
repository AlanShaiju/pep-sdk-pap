package com.example.pep.sdk.sync;

import com.example.pep.sdk.core.annotation.CommunicationMode;
import com.example.pep.sdk.core.model.Operation;
import com.example.pep.sdk.core.model.PapEntityChange;
import com.example.pep.sdk.core.model.PapEntityDescriptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transaction-scoped buffer. Coalesces multiple touches of the same entity within one transaction.
 * Drained once by the synchronization at beforeCommit.
 *
 * <p>Coalescing rules per the design (§8.3 / §9.4): the net operation's mode is read from the
 * descriptor for that operation, not from the contributing changes.
 */
public final class ChangeBuffer {

    record Key(Class<?> entityClass, String entityId) { }

    private final Map<Key, PapEntityChange> changes = new LinkedHashMap<>();

    public void append(PapEntityChange next, PapEntityDescriptor descriptor) {
        Key key = new Key(next.entityClass(), next.entityId());
        PapEntityChange existing = changes.get(key);
        if (existing == null) { changes.put(key, next); return; }

        PapEntityChange merged = coalesce(existing, next, descriptor);
        if (merged == null) changes.remove(key);
        else changes.put(key, merged);
    }

    static PapEntityChange coalesce(PapEntityChange prev, PapEntityChange next, PapEntityDescriptor d) {
        Operation netOp = switch (prev.operation()) {
            case CREATE -> switch (next.operation()) {
                case CREATE, UPDATE -> Operation.CREATE;
                case DELETE -> null;                       // CREATE+DELETE -> erase
            };
            case UPDATE -> switch (next.operation()) {
                case CREATE, UPDATE -> Operation.UPDATE;
                case DELETE -> Operation.DELETE;
            };
            case DELETE -> switch (next.operation()) {
                case CREATE, UPDATE -> Operation.UPDATE;   // re-creation
                case DELETE -> Operation.DELETE;
            };
        };
        if (netOp == null) return null;

        CommunicationMode mode = d.modeFor(netOp);
        Map<String, Object> src = netOp == Operation.DELETE ? prev.sources() : next.sources();
        return new PapEntityChange(prev.entityClass(), prev.entityId(), netOp, mode, src);
    }

    public Collection<PapEntityChange> drain() {
        List<PapEntityChange> out = new ArrayList<>(changes.values());
        changes.clear();
        return out;
    }

    public boolean isEmpty() { return changes.isEmpty(); }
}
