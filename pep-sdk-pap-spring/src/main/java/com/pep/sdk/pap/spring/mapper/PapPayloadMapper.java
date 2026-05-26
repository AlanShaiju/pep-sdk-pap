package com.pep.sdk.pap.spring.mapper;

import com.pep.sdk.pap.model.PapEntity;
import com.pep.sdk.pap.model.PapEvent;
import com.pep.sdk.pap.model.PolicyDataPayload;

/**
 * Interface that custom bean mappers implement to convert domain objects/arguments
 * into a structured PolicyDataPayload for the PAP.
 */
public interface PapPayloadMapper {

    /**
     * The type of authorization entity this mapper handles.
     */
    PapEntity getEntity();

    /**
     * The event operation this mapper handles.
     */
    PapEvent getEvent();

    /**
     * Maps the method's return value and its original execution arguments to a typed payload.
     *
     * @param result The object returned by the annotated method.
     * @param args   The arguments passed to the annotated method.
     * @return The constructed PolicyDataPayload.
     */
    PolicyDataPayload map(Object result, Object[] args);
}
