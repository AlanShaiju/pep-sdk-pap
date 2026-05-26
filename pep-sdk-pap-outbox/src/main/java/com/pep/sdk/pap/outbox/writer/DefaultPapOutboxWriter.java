package com.pep.sdk.pap.outbox.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pep.sdk.pap.model.PapEntity;
import com.pep.sdk.pap.model.PapEvent;
import com.pep.sdk.pap.model.PapRequest;
import com.pep.sdk.pap.outbox.PapOutboxWriter;
import com.pep.sdk.pap.outbox.model.OutboxRecord;
import com.pep.sdk.pap.outbox.model.OutboxStatus;
import com.pep.sdk.pap.outbox.repository.PapOutboxRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Service implementation for writing records to the transaction outbox.
 */
@Service
public class DefaultPapOutboxWriter implements PapOutboxWriter {

    private static final Logger log = LoggerFactory.getLogger(DefaultPapOutboxWriter.class);

    private final PapOutboxRepository repository;
    private final ObjectMapper objectMapper;

    public DefaultPapOutboxWriter(PapOutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void write(PapEntity entity, PapEvent event, PapRequest request, String errorMessage) {
        log.info("Writing PAP notification to outbox for entity {} and event {}", entity, event);

        try {
            String payloadJson = "";
            if (request.getBody() != null) {
                if (request.getBody() instanceof String) {
                    payloadJson = (String) request.getBody();
                } else {
                    payloadJson = objectMapper.writeValueAsString(request.getBody());
                }
            }

            OutboxRecord record = new OutboxRecord();
            record.setEntity(entity);
            record.setEvent(event);
            record.setPayloadJson(payloadJson);
            record.setHttpMethod(request.getMethod());
            record.setEndpointPath(request.getResolvedPath());
            record.setStatus(OutboxStatus.PENDING);
            record.setLastFailedAt(Instant.now());
            record.setErrorMessage(errorMessage);

            repository.save(record);
            log.debug("Successfully saved outbox record with ID {}", record.getId());

        } catch (Exception e) {
            log.error("Failed to write outbox record to DB", e);
            throw new RuntimeException("Persistence of PAP outbox record failed", e);
        }
    }
}
