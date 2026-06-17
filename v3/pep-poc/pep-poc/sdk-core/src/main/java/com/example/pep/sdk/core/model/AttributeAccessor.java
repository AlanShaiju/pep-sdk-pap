package com.example.pep.sdk.core.model;

import com.example.pep.sdk.core.exception.PapSdkException;

import java.lang.reflect.Field;

/** Cached reflective accessor for one @PapAttribute field. */
public final class AttributeAccessor {

    private final String attributeName;
    private final Field field;
    private final boolean idAttribute;

    public AttributeAccessor(String attributeName, Field field, boolean idAttribute) {
        this.attributeName = attributeName;
        this.field = field;
        this.idAttribute = idAttribute;
        this.field.setAccessible(true);
    }

    public String attributeName() { return attributeName; }
    public boolean isIdAttribute() { return idAttribute; }

    public Object read(Object entity) {
        try {
            return field.get(entity);
        } catch (IllegalAccessException e) {
            throw new PapSdkException("Failed to read field " + field.getName(), e);
        }
    }
}
