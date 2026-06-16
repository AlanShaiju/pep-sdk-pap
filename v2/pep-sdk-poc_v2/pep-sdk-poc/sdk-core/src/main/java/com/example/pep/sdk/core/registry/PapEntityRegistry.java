package com.example.pep.sdk.core.registry;

import com.example.pep.sdk.core.model.PapEntityDescriptor;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class PapEntityRegistry {

    private final Map<Class<?>, PapEntityDescriptor> byClass;
    private final Map<String, PapEntityDescriptor> byPapEntity;

    public PapEntityRegistry(Map<Class<?>, PapEntityDescriptor> byClass) {
        this.byClass = Map.copyOf(byClass);
        this.byPapEntity = byClass.values().stream()
                .collect(Collectors.toUnmodifiableMap(PapEntityDescriptor::papEntity, d -> d));
    }

    public Optional<PapEntityDescriptor> find(Class<?> entityClass) {
        return Optional.ofNullable(byClass.get(entityClass));
    }

    public Optional<PapEntityDescriptor> findByPapEntity(String papEntity) {
        return Optional.ofNullable(byPapEntity.get(papEntity));
    }

    public Collection<PapEntityDescriptor> all() { return byClass.values(); }
    public int size()                            { return byClass.size(); }
}
