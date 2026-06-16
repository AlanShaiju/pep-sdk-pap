package com.example.pep.sdk.sync;

import com.example.pep.sdk.core.model.PapEntityChange;
import com.example.pep.sdk.core.model.PapEntityDescriptor;

/**
 * Pluggable target for ASYNC dispatch. Implementation lives in sdk-async; sdk-sync
 * stays free of a JPA dependency by talking through this SPI.
 *
 * <p>Implementations must run in the caller's DB transaction.
 */
public interface OutboxAppender {
    void append(PapEntityChange change, PapEntityDescriptor descriptor);
}
