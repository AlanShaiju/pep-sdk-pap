package com.example.pep.sdk.sync;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Divergence rows are written once (batched, in TX2) and never updated or deleted, so this
 * repository needs only the inherited {@code saveAll}/{@code findAll}. The previous design's
 * deletePending / markRejected methods are gone — resolution now lives in STL_PEP_TRANSACTION.
 */
public interface PapDivergenceRepository extends JpaRepository<PapDivergenceEntry, Integer> {
}
