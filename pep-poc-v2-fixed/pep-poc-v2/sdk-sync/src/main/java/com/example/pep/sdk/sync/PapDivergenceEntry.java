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
 * STL_PEP_DIVERGENCE — one row per PAP call in a transaction, recorded for auditing.
 *
 * <p>New-design semantics (differs from the per-entity-PENDING approach): every attempted or
 * skipped PAP call produces exactly one row, written once in TX2 and never deleted:
 * <ul>
 *   <li>{@code SUCCESS} — the PAP confirmed the write (response body captured in {@code response}).</li>
 *   <li>{@code FAILED}  — the PAP rejected or was unavailable, OR the call was never attempted
 *       because an earlier call in the same transaction already failed (reason in {@code response}).</li>
 * </ul>
 * The overall fate of the transaction lives in STL_PEP_TRANSACTION, keyed by the same
 * {@code transactionId}; a divergence row's {@code SUCCESS} means "present in PAP", which—if the
 * transaction is FAILED—is exactly the divergence an SRE needs to reconcile.
 */
@Entity
@Table(name = "STL_PEP_DIVERGENCE")
public class PapDivergenceEntry {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED  = "FAILED";

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
    @Column(name = "response")
    private String response;

    protected PapDivergenceEntry() { }

    private PapDivergenceEntry(Integer transactionId, String entityType, String operation,
                              String payload, Instant createdAt, String status, String response) {
        this.transactionId = transactionId;
        this.entityType = entityType;
        this.operation = operation;
        this.payload = payload;
        this.createdAt = createdAt;
        this.status = status;
        this.response = response;
    }

    /** A PAP call that the PAP confirmed; {@code response} holds the PAP's response body. */
    public static PapDivergenceEntry success(Integer transactionId, String entityType, String operation,
                                             String payload, Instant createdAt, String response) {
        return new PapDivergenceEntry(transactionId, entityType, operation, payload, createdAt, SUCCESS, response);
    }

    /** A PAP call that failed or was never attempted; {@code response} holds the reason. */
    public static PapDivergenceEntry failed(Integer transactionId, String entityType, String operation,
                                            String payload, Instant createdAt, String response) {
        return new PapDivergenceEntry(transactionId, entityType, operation, payload, createdAt, FAILED, response);
    }

    public Integer getId()            { return id; }
    public Integer getTransactionId() { return transactionId; }
    public String getEntityType()     { return entityType; }
    public String getOperation()      { return operation; }
    public String getPayload()        { return payload; }
    public Instant getCreatedAt()     { return createdAt; }
    public String getStatus()         { return status; }
    public String getResponse()       { return response; }
}
