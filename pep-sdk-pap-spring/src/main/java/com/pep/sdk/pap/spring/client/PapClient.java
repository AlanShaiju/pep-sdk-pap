package com.pep.sdk.pap.spring.client;

import com.pep.sdk.pap.model.PapRequest;
import com.pep.sdk.pap.model.PapResponse;

/**
 * Client interface for making HTTP calls to the PAP.
 */
public interface PapClient {

    /**
     * Executes the PapRequest against the PAP API.
     *
     * @param request The descriptor containing request method, path, headers, query parameters, and body.
     * @return The PapResponse representing the outcomes of the execution.
     */
    PapResponse execute(PapRequest request);
}
