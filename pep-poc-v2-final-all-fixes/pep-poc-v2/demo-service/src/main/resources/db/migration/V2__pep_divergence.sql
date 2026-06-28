-- ============================================================================
-- STL_PEP_TRANSACTION — the effective state of each PEP transaction.
-- One row per transaction_id. PENDING at creation (TX2), then resolved to
-- SUCCESS (TX3, after local commit) or FAILED (TX2 on PAP failure, or TX3 if
-- the local commit itself fails).
-- ============================================================================
CREATE SEQUENCE pep_transaction_seq;

CREATE TABLE STL_PEP_TRANSACTION (
    transaction_id  INTEGER      PRIMARY KEY,
    status          VARCHAR(15)  NOT NULL,   -- PENDING | SUCCESS | FAILED
    reason          TEXT,                    -- failure reason, when status = FAILED
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);

CREATE INDEX idx_stl_pep_transaction_status ON STL_PEP_TRANSACTION (status);

-- ============================================================================
-- STL_PEP_DIVERGENCE — one row per PAP call attempted (or skipped) in a
-- transaction. Unlike the previous design (PENDING rows that get deleted on
-- success), here every call's outcome is recorded for auditing:
--   SUCCESS — PAP confirmed the write.
--   FAILED  — PAP rejected/was unavailable, OR the call was skipped because an
--             earlier call in the same transaction already failed.
-- Rows are written once, in TX2, and never deleted.
-- ============================================================================
CREATE TABLE STL_PEP_DIVERGENCE (
    id              SERIAL       PRIMARY KEY,
    transaction_id  INTEGER      NOT NULL REFERENCES STL_PEP_TRANSACTION (transaction_id),
    entity_type     VARCHAR(50)  NOT NULL,
    operation       VARCHAR(50)  NOT NULL,
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    status          VARCHAR(15)  NOT NULL,   -- SUCCESS | FAILED
    response        JSONB                    -- PAP response on success, or failure reason
);

CREATE INDEX idx_stl_pep_divergence_transaction ON STL_PEP_DIVERGENCE (transaction_id);
CREATE INDEX idx_stl_pep_divergence_status      ON STL_PEP_DIVERGENCE (status);
