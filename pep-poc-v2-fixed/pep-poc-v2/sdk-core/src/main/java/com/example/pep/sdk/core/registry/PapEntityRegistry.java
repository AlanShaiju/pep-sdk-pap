package com.example.pep.sdk.core.registry;

import com.example.pep.sdk.core.model.PapEntityDescriptor;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public final class PapEntityRegistry {

    private final Map<Class<?>, PapEntityDescriptor> byClass;

    public PapEntityRegistry(Map<Class<?>, PapEntityDescriptor> byClass) {
        this.byClass = Map.copyOf(byClass);
    }

    public Optional<PapEntityDescriptor> find(Class<?> entityClass) {
        return Optional.ofNullable(byClass.get(entityClass));
    }

    public Collection<PapEntityDescriptor> all() { return byClass.values(); }
    public int size()                            { return byClass.size(); }
}
