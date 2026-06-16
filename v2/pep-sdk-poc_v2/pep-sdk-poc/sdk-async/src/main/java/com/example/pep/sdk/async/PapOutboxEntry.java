package com.example.pep.sdk.async;

import com.example.pep.sdk.core.model.Operation;
import com.example.pep.sdk.core.model.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "STL_PEP_OUTBOX")
public class PapOutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 50)
    private Operation operation;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "header", nullable = false, columnDefinition = "text")
    private String header;

    @Column(name = "path_variable", nullable = false, columnDefinition = "text")
    private String pathVariable;

    @Column(name = "request_param", nullable = false, columnDefinition = "text")
    private String requestParam;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OutboxStatus status;

    protected PapOutboxEntry() { }

    public PapOutboxEntry(String entityType, Operation operation,
                          String payload, String header, String pathVariable, String requestParam,
                          Instant now) {
        this.entityType = entityType;
        this.operation = operation;
        this.payload = payload;
        this.header = header;
        this.pathVariable = pathVariable;
        this.requestParam = requestParam;
        this.attemptCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
        this.status = OutboxStatus.PENDING;
    }

    public Long getId()                  { return id; }
    public String getEntityType()        { return entityType; }
    public Operation getOperation()      { return operation; }
    public String getPayload()           { return payload; }
    public String getHeader()            { return header; }
    public String getPathVariable()      { return pathVariable; }
    public String getRequestParam()      { return requestParam; }
    public int getAttemptCount()         { return attemptCount; }
    public Instant getCreatedAt()        { return createdAt; }
    public Instant getUpdatedAt()        { return updatedAt; }
    public OutboxStatus getStatus()      { return status; }

    public void markRejected(Instant now)     { this.status = OutboxStatus.REJECTED_DATA;  this.updatedAt = now; }
    public void markDeadLetter(Instant now)   { this.status = OutboxStatus.DEAD_LETTER;     this.updatedAt = now; }
    public void incrementAttempt(Instant now) { this.attemptCount++;                         this.updatedAt = now; }
}
