package com.example.pep.sdk.core.model;

import com.example.pep.sdk.core.exception.PapSdkException;

import java.lang.reflect.Field;

/**
 * One @PapInclude entry: a source name plus a pre-resolved chain of Fields to walk.
 * The first Field is the relationship field on the owning entity; subsequent Fields
 * follow the dot-path segments of @PapInclude.attribute.
 *
 * <p>Null-safe: a null at any hop yields null (the include contributes nothing).
 */
public final class IncludeAccessor {

    private final String name;
    private final Field[] path;

    public IncludeAccessor(String name, Field[] path) {
        this.name = name;
        this.path = path;
        for (Field f : path) f.setAccessible(true);
    }

    public String name() { return name; }

    public Object evaluate(Object root) {
        Object current = root;
        for (Field f : path) {
            if (current == null) return null;
            try {
                current = f.get(current);
            } catch (IllegalAccessException e) {
                throw new PapSdkException("Failed to read " + f.getName() + " for include '" + name + "'", e);
            }
        }
        return current;
    }
}
