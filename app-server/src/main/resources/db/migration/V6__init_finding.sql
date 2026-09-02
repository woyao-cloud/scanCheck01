CREATE TABLE finding (
    id              BIGSERIAL PRIMARY KEY,
    scan_task_id    BIGINT       NOT NULL,
    engine          VARCHAR(32)  NOT NULL,
    rule_code       VARCHAR(128) NOT NULL,
    rule_name       VARCHAR(256),
    file_path       TEXT         NOT NULL,
    line_number     INT,
    severity        VARCHAR(16)  NOT NULL DEFAULT 'LOW',
    category        VARCHAR(64),
    message         TEXT,
    code_snippet    TEXT,
    fingerprint     VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    first_seen_at   TIMESTAMP    NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMP    NOT NULL DEFAULT now(),
    occurrence_count INT         NOT NULL DEFAULT 1,
    raw_json        JSONB,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_finding_fp ON finding (fingerprint);
CREATE INDEX idx_finding_scan ON finding (scan_task_id);

CREATE TABLE finding_trace (
    id          BIGSERIAL PRIMARY KEY,
    finding_id  BIGINT       NOT NULL,
    scan_task_id BIGINT      NOT NULL,
    action      VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_trace_finding ON finding_trace (finding_id);
