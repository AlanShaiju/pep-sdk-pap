package com.pep.sdk.pap.annotation;

import com.pep.sdk.pap.model.FailBehavior;
import com.pep.sdk.pap.model.PapEntity;
import com.pep.sdk.pap.model.PapEvent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark service methods whose return values and arguments
 * should trigger a policy sync to the central Policy Administration Point (PAP).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface NotifyPap {

    /**
     * The type of authorization entity produced or modified by this method.
     */
    PapEntity entity();

    /**
     * The specific event operation performed on the entity.
     */
    PapEvent event();

    /**
     * Behavior applied when the PAP HTTP call fails or retries are exhausted.
     */
    FailBehavior failBehavior() default FailBehavior.FALLBACK_TO_OUTBOX;

    /**
     * Name of the compensating bean method in the same class to invoke
     * when failBehavior is FailBehavior.COMPENSATE.
     */
    String compensateWith() default "";

    /**
     * Timeout duration in seconds for the PAP HTTP request.
     */
    int timeoutSeconds() default 5;

    /**
     * If true, makes the complete PapResponse available in PapResponseHolder
     * for the service layer to inspect.
     */
    boolean propagateResponse() default false;
}
