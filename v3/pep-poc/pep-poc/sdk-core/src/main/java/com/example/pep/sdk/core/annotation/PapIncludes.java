package com.example.pep.sdk.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Container for repeated {@link PapInclude}. Compiler-synthesized; not used directly. */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PapIncludes {
    PapInclude[] value();
}
