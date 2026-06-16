package com.example.pep.sdk.async;

import com.example.pep.sdk.core.model.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PapOutboxRepository extends JpaRepository<PapOutboxEntry, Long> {

    /**
     * Claim a batch of PENDING rows using FOR UPDATE SKIP LOCKED.
     * The transaction must be active when this is called; the locks are released on commit.
     */
    @Query(value = """
            SELECT * FROM STL_PEP_OUTBOX
             WHERE status = 'PENDING'
             ORDER BY created_at
             LIMIT :batchSize
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PapOutboxEntry> claimBatch(@Param("batchSize") int batchSize);

    List<PapOutboxEntry> findByStatus(OutboxStatus status);
}
