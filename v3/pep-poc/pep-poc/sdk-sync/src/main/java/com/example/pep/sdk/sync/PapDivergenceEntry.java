package com.example.pep.sdk.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * STL_PEP_DIVERGENCE — records PAP writes that succeeded while the corresponding local
 * transaction did not durably commit (SYNC dual-write detection).
 *
 * <p>Rows are written PENDING the moment a PAP call succeeds (before the local transaction's
 * outcome is known), then resolved by {@code transactionId} — deleted on commit, flipped to
 * REJECTED_DATA on rollback.
 */
@Entity
@Table(name = "STL_PEP_DIVERGENCE")
public class PapDivergenceEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "transaction_id", nullable = false)
    private Integer transactionId;

    @Column(name = "entity_type", length = 50, nullable = false)
    private String entityType;

    @Column(name = "operation", length = 50, nullable = false)
    private String operation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "status", length = 15, nullable = false)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error")
    private String error;

    protected PapDivergenceEntry() { }

    public PapDivergenceEntry(Integer transactionId, String entityType, String operation,
                              String payload, Instant createdAt) {
        this.transactionId = transactionId;
        this.entityType = entityType;
        this.operation = operation;
        this.payload = payload;
        this.createdAt = createdAt;
        this.status = "PENDING";
    }

    public Integer getId()            { return id; }
    public Integer getTransactionId() { return transactionId; }
    public String getEntityType()     { return entityType; }
    public String getOperation()      { return operation; }
    public String getPayload()        { return payload; }
    public Instant getCreatedAt()     { return createdAt; }
    public String getStatus()         { return status; }
    public String getError()          { return error; }
}
