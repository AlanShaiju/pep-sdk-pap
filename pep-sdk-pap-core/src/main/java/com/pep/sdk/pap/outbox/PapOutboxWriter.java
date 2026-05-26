package com.pep.sdk.pap.outbox;

import com.pep.sdk.pap.model.PapEntity;
import com.pep.sdk.pap.model.PapEvent;
import com.pep.sdk.pap.model.PapRequest;

/**
 * Interface for transactionally writing payloads to the outbox database table.
 * Implemented by the pep-sdk-pap-outbox module.
 */
public interface PapOutboxWriter {

    /**
     * Writes the failed or deferred request details into the outbox table.
     *
     * @param entity       The PapEntity type.
     * @param event        The PapEvent type.
     * @param request      The PapRequest containing path, method, headers, query parameters, and body.
     * @param errorMessage Optional error message describing why it's being written to the outbox.
     */
    void write(PapEntity entity, PapEvent event, PapRequest request, String errorMessage);
}
