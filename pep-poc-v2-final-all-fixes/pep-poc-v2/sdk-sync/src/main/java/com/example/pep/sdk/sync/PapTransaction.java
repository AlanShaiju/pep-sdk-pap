package com.example.pep.sdk.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * STL_PEP_TRANSACTION — the effective state of one PEP transaction, keyed by {@code transactionId}.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@code PENDING} — created at the start of TX2, before any PAP call is attempted.</li>
 *   <li>{@code FAILED}  — set within TX2 if any PAP call fails (PAP rejected or unavailable), or
 *       set in TX3/afterCompletion if the local business transaction (TX1) fails to commit even
 *       though every PAP call succeeded.</li>
 *   <li>{@code SUCCESS} — set in TX3 once TX1 has durably committed and all PAP calls succeeded.</li>
 * </ul>
 */
@Entity
@Table(name = "STL_PEP_TRANSACTION")
public class PapTransaction {

    public static final String PENDING = "PENDING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED  = "FAILED";

    @Id
    @Column(name = "transaction_id")
    private Integer transactionId;

    @Column(name = "status", length = 15, nullable = false)
    private String status;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PapTransaction() { }

    public PapTransaction(Integer transactionId, Instant now) {
        this.transactionId = transactionId;
        this.status = PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markSuccess(Instant now) {
        this.status = SUCCESS;
        this.updatedAt = now;
    }

    public void markFailed(String reason, Instant now) {
        this.status = FAILED;
        this.reason = reason;
        this.updatedAt = now;
    }

    public Integer getTransactionId() { return transactionId; }
    public String getStatus()         { return status; }
    public String getReason()         { return reason; }
    public Instant getCreatedAt()     { return createdAt; }
    public Instant getUpdatedAt()     { return updatedAt; }
}
