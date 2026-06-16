package com.example.pep.sdk.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PapEntity {

    /** The PAP entity type. Must exist in the bundled catalog. */
    String entity();

    /** Static key-value pairs propagated on every request for this entity. */
    PapProperty[] properties() default {};

    /** Per-operation communication mode. Operations not listed fall back to the global default. */
    PapOperationMode[] operationModes() default {};
}
