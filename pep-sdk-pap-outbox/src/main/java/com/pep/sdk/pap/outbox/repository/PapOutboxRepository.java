package com.pep.sdk.pap.outbox.repository;

import com.pep.sdk.pap.outbox.model.OutboxRecord;
import com.pep.sdk.pap.outbox.model.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA Repository for querying and executing CRUD operations on the 'pep_pap_outbox' table.
 */
@Repository
public interface PapOutboxRepository extends JpaRepository<OutboxRecord, UUID> {

    /**
     * Finds all outbox records that match the given status (e.g. PENDING).
     */
    List<OutboxRecord> findByStatus(OutboxStatus status);
}
