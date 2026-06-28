CREATE TABLE tenant (
    id   BIGINT       PRIMARY KEY,
    code VARCHAR(255),
    name VARCHAR(255)
);

CREATE TABLE pipeline (
    id          SERIAL       PRIMARY KEY,
    code        VARCHAR(255),
    name        VARCHAR(255),
    description VARCHAR(255),
    tenant_id   BIGINT REFERENCES tenant(id)
);

CREATE TABLE deployment (
    id          SERIAL       PRIMARY KEY,
    code        VARCHAR(255),
    name        VARCHAR(255),
    description VARCHAR(255),
    tenant_id   BIGINT REFERENCES tenant(id)
);

CREATE TABLE monitor (
    id          SERIAL       PRIMARY KEY,
    code        VARCHAR(255),
    name        VARCHAR(255),
    description VARCHAR(255),
    tenant_id   BIGINT REFERENCES tenant(id)
);
