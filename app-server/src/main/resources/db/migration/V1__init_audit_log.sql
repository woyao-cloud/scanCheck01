CREATE TABLE audit_log (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT,
    action        VARCHAR(64)  NOT NULL,
    module        VARCHAR(64)  NOT NULL,
    resource_type VARCHAR(64),
    resource_id   BIGINT,
    detail        JSONB,
    ip            VARCHAR(64),
    occurred_at   TIMESTAMP    NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_resource ON audit_log (resource_type, resource_id);
