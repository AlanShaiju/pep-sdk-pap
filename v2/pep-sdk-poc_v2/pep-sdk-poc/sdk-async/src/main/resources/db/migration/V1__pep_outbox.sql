CREATE TABLE STL_PEP_OUTBOX (
    id              BIGSERIAL    PRIMARY KEY,
    entity_type     VARCHAR(255) NOT NULL,
    operation       VARCHAR(50)  NOT NULL,
    payload         TEXT         NOT NULL,
    header          TEXT         NOT NULL,
    path_variable   TEXT         NOT NULL,
    request_param   TEXT         NOT NULL,
    attempt_count   INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    status          VARCHAR(16)  NOT NULL
);

CREATE INDEX idx_stl_pep_outbox_status_created ON STL_PEP_OUTBOX (status, created_at);
CREATE INDEX idx_stl_pep_outbox_entity_type    ON STL_PEP_OUTBOX (entity_type);
