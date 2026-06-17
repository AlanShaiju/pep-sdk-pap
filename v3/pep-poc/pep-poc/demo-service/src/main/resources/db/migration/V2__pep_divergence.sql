CREATE SEQUENCE pap_divergence_transaction_seq;

CREATE TABLE STL_PEP_DIVERGENCE (
    id              SERIAL       PRIMARY KEY,
    transaction_id  INTEGER      NOT NULL,
    entity_type     VARCHAR(50)  NOT NULL,
    operation       VARCHAR(50)  NOT NULL,
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    status          VARCHAR(15)  NOT NULL,
    error           JSONB
);

CREATE INDEX idx_stl_pep_divergence_transaction ON STL_PEP_DIVERGENCE (transaction_id);
CREATE INDEX idx_stl_pep_divergence_status      ON STL_PEP_DIVERGENCE (status);
