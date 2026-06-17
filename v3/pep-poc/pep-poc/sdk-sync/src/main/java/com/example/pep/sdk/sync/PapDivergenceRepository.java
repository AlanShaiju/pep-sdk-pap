package com.example.pep.sdk.sync;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PapDivergenceRepository extends JpaRepository<PapDivergenceEntry, Integer> {

    /** Local transaction committed — every PENDING row for this id was a false alarm. */
    @Modifying
    @Query("DELETE FROM PapDivergenceEntry d WHERE d.transactionId = :txId AND d.status = 'PENDING'")
    void deletePendingByTransactionId(@Param("txId") int txId);

    /** Local transaction rolled back — every PENDING row for this id is now confirmed stale at the PAP. */
    @Modifying
    @Query("UPDATE PapDivergenceEntry d SET d.status = 'REJECTED_DATA', d.error = :error "
            + "WHERE d.transactionId = :txId AND d.status = 'PENDING'")
    void markRejectedByTransactionId(@Param("txId") int txId, @Param("error") String error);
}
