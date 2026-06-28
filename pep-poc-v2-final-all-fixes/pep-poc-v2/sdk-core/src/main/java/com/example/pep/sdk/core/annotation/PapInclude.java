package com.example.pep.sdk.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Pulls one named value from a related entity into the owning entity's source map.
 *
 * <p>Place on a single-valued relationship field ({@code @ManyToOne} / {@code @OneToOne}).
 * Repeatable — stack multiple to pull multiple values from the same relationship.
 *
 * <pre>
 * &#64;ManyToOne(fetch = FetchType.EAGER)
 * &#64;PapInclude(name = "tenant_id",   attribute = "id")
 * &#64;PapInclude(name = "tenant_code", attribute = "code")
 * private Tenant tenant;
 * </pre>
 *
 * <p>{@code attribute} supports dot-paths for nested walks: {@code attribute = "region.code"}
 * reads {@code entity.relation.region.code}.
 *
 * <p>Precedence: a {@code @PapProperty} with the same key, or a {@code @PapAttribute} with the
 * same name on the owning class, wins over this include.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(PapIncludes.class)
public @interface PapInclude {

    /** Source name in the owning entity's source map. The catalog references this. */
    String name();

    /** Java field name on the related entity. Dot-paths allowed: "region.code". */
    String attribute();
}
