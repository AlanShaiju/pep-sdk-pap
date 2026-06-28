package com.example.pep.sdk.core.model;

import com.example.pep.sdk.core.exception.PapSdkException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Evaluates relationship paths via public getter methods (no reflection on private fields).
 * 
 * Example: For path "tenant.id" on class Pipeline:
 * 1. Invoke Pipeline.getTenant() → returns Tenant instance
 * 2. Invoke Tenant.getId() → returns Integer id
 * 
 * All methods must be public and follow JavaBean naming conventions.
 * 
 * This approach:
 * - Eliminates need for setAccessible(true)
 * - Preserves encapsulation
 * - Null-safe (returns null if any hop in path is null)
 * - Follows JPA best practices
 * 
 * @see com.example.pep.sdk.core.annotation.PapInclude
 */
public final class IncludeAccessor {

    private final String name;
    private final Method[] getterMethods;

    /**
     * Constructs an accessor for a relationship path via public getter methods.
     * 
     * @param name the PAP attribute name (e.g., "tenant_id")
     * @param getterMethods array of public getter methods forming the path
     *                       Example: [Pipeline.getTenant(), Tenant.getId()]
     *                       Must have at least one method, none can be null
     * @throws NullPointerException if name is null, getterMethods is null, 
     *                              or any getter method is null
     * @throws IllegalArgumentException if getterMethods is empty
     */
    public IncludeAccessor(String name, Method[] getterMethods) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.getterMethods = Objects.requireNonNull(getterMethods, 
            "getterMethods cannot be null").clone();
        
        if (this.getterMethods.length == 0) {
            throw new IllegalArgumentException("getterMethods cannot be empty");
        }
        
        for (int i = 0; i < this.getterMethods.length; i++) {
            if (this.getterMethods[i] == null) {
                throw new NullPointerException(
                    "getter method at index " + i + " is null for include: " + name);
            }
        }
    }

    /**
     * Returns the PAP attribute name.
     * 
     * @return the attribute name (e.g., "tenant_id")
     */
    public String name() { 
        return name; 
    }

    /**
     * Evaluates the dot-path by invoking getter methods in sequence.
     * 
     * Returns null at any step where the current object is null (null-safe).
     * 
     * Example:
     * <pre>
     *   root = Pipeline instance
     *   step 1: root.getTenant() → Tenant instance
     *   step 2: tenant.getId() → Integer (final value)
     *   returns: id value
     * </pre>
     * 
     * @param root the root entity instance to start evaluation from
     * @return the final value after traversing all getter methods, 
     *         or null if any step returns null
     * @throws PapSdkException if any getter method invocation fails
     */
    public Object evaluate(Object root) {
        Object current = root;
        
        for (int i = 0; i < getterMethods.length; i++) {
            if (current == null) {
                return null;
            }
            
            try {
                current = getterMethods[i].invoke(current);
            } catch (IllegalAccessException e) {
                throw new PapSdkException(
                    "Cannot invoke getter at step " + i + " for include path: " + name, e);
            } catch (InvocationTargetException e) {
                throw new PapSdkException(
                    "Getter threw exception at step " + i + " for include path: " + name,
                    e.getCause());
            }
        }
        
        return current;
    }
}
