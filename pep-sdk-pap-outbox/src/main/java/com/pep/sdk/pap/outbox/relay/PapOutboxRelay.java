package com.pep.sdk.pap.outbox.relay;

import com.pep.sdk.pap.model.PapRequest;
import com.pep.sdk.pap.model.PapResponse;
import com.pep.sdk.pap.outbox.model.OutboxRecord;
import com.pep.sdk.pap.outbox.model.OutboxStatus;
import com.pep.sdk.pap.outbox.repository.PapOutboxRepository;
import com.pep.sdk.pap.spring.client.PapClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Scheduler that polls the 'pep_pap_outbox' table for pending notifications
 * and dispatches them to the PAP.
 */
@Component
public class PapOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(PapOutboxRelay.class);

    private final PapOutboxRepository repository;
    private final PapClient papClient;

    @Value("${pep.pap.outbox.relay.max-retries:5}")
    private int maxRetries;

    public PapOutboxRelay(PapOutboxRepository repository, PapClient papClient) {
        this.repository = repository;
        this.papClient = papClient;
    }

    /**
     * Periodically polls the outbox table for PENDING records and dispatches them.
     */
    @Scheduled(fixedDelayString = "${pep.pap.outbox.relay.delay-ms:5000}")
    @Transactional
    public void pollAndRelay() {
        log.trace("Polling outbox for pending PAP requests...");
        List<OutboxRecord> pendingRecords = repository.findByStatus(OutboxStatus.PENDING);

        if (pendingRecords.isEmpty()) {
            return;
        }

        log.info("Found {} pending outbox records to process", pendingRecords.size());

        for (OutboxRecord record : pendingRecords) {
            processRecord(record);
        }
    }

    private void processRecord(OutboxRecord record) {
        log.debug("Processing outbox record {} for {} - {}", record.getId(), record.getEntity(), record.getEvent());

        // Reconstruct the request
        PapRequest request = PapRequest.builder()
                .method(record.getHttpMethod())
                .path(record.getEndpointPath())
                .header("Content-Type", "application/json")
                .body(record.getPayloadJson())
                .build();

        try {
            PapResponse response = papClient.execute(request);

            if (response.isSuccess()) {
                log.info("Successfully dispatched outbox record {}. Deleting from DB.", record.getId());
                repository.delete(record);
            } else {
                handleFailure(record, response);
            }
        } catch (Exception ex) {
            log.error("Unexpected error executing outbox request for ID: " + record.getId(), ex);
            PapResponse response = PapResponse.builder()
                    .statusCode(500)
                    .success(false)
                    .errorMessage(ex.getMessage() != null ? ex.getMessage() : ex.toString())
                    .build();
            handleFailure(record, response);
        }
    }

    private void handleFailure(OutboxRecord record, PapResponse response) {
        int nextRetryCount = record.getRetryCount() + 1;
        record.setRetryCount(nextRetryCount);
        record.setLastFailedAt(Instant.now());
        record.setErrorMessage(response.getErrorMessage());

        int statusCode = response.getStatusCode();
        
        // Terminal client errors (400, 404) mean the data is invalid/rejected
        if (statusCode == 400 || statusCode == 404) {
            log.error("Outbox record {} rejected terminal response {}. Marking as REJECTED_DATA.", record.getId(), statusCode);
            record.setStatus(OutboxStatus.REJECTED_DATA);
        } else if (nextRetryCount >= maxRetries) {
            log.error("Outbox record {} reached max retries. Marking as DEAD_LETTER.", record.getId());
            record.setStatus(OutboxStatus.DEAD_LETTER);
        } else {
            log.warn("Failed to dispatch outbox record {}. Will retry. Current attempts: {}", record.getId(), nextRetryCount);
            record.setStatus(OutboxStatus.PENDING);
        }

        repository.save(record);
    }
}
