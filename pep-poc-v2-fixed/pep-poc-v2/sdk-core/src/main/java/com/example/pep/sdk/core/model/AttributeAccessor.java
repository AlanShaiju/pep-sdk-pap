package com.example.pep.sdk.core.model;

import com.example.pep.sdk.core.exception.PapSdkException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Accesses entity attributes via public getter methods (no reflection on private fields).
 * 
 * Requires entities to have public getter methods following JavaBean conventions:
 * field "code" → public method "getCode()", field "id" → public method "getId()".
 * 
 * This approach:
 * - Eliminates need for setAccessible(true)
 * - Preserves encapsulation
 * - Follows JPA best practices
 * - Avoids security concerns with reflection bypass
 * 
 * @see com.example.pep.sdk.core.annotation.PapAttribute
 */
public final class AttributeAccessor {

    private final String attributeName;
    private final Method getterMethod;
    private final boolean idAttribute;

    /**
     * Constructs an accessor for an entity attribute via its public getter method.
     * 
     * @param attributeName the PAP attribute name (e.g., "id", "resource_name")
     * @param getterMethod the public getter method (e.g., getId, getCode)
     *                     Must be a no-arg method returning the attribute value
     * @param idAttribute true if this is the entity's @Id field
     * @throws NullPointerException if getterMethod is null
     */
    public AttributeAccessor(String attributeName, Method getterMethod, boolean idAttribute) {
        this.attributeName = Objects.requireNonNull(attributeName, "attributeName cannot be null");
        this.getterMethod = Objects.requireNonNull(getterMethod, 
            "getterMethod cannot be null for attribute: " + attributeName);
        this.idAttribute = idAttribute;
    }

    /**
     * Returns the PAP source attribute name.
     * 
     * @return the attribute name (e.g., "id", "resource_instance_code")
     */
    public String attributeName() { 
        return attributeName; 
    }

    /**
     * Checks if this accessor is for the entity's ID field.
     * 
     * @return true if this is the @Id-annotated field
     */
    public boolean isIdAttribute() { 
        return idAttribute; 
    }

    /**
     * Reads the attribute value from an entity instance via the public getter method.
     * 
     * @param entity the entity instance to read from (must not be null)
     * @return the attribute value, or null if getter returns null
     * @throws PapSdkException if getter method invocation fails
     * @throws NullPointerException if entity is null
     */
    public Object read(Object entity) {
        Objects.requireNonNull(entity, "entity cannot be null");
        
        try {
            return getterMethod.invoke(entity);
        } catch (IllegalAccessException e) {
            throw new PapSdkException(
                "Cannot invoke getter for attribute: " + attributeName, e);
        } catch (InvocationTargetException e) {
            throw new PapSdkException(
                "Getter threw exception for attribute: " + attributeName, 
                e.getCause());
        }
    }
}
