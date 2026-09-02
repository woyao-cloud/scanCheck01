CREATE TABLE scan_task (
    id            BIGSERIAL PRIMARY KEY,
    project_id    BIGINT      NOT NULL,
    repo_id       BIGINT,
    engine        VARCHAR(32) NOT NULL,
    ref           VARCHAR(128),
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    trigger_type  VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    created_by    BIGINT,
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP,
    error_message VARCHAR(512),
    finding_count INT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_scan_project ON scan_task (project_id, created_at);

CREATE TABLE scan_job (
    id            BIGSERIAL PRIMARY KEY,
    scan_task_id  BIGINT      NOT NULL,
    engine        VARCHAR(32) NOT NULL,
    job_status    VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP,
    duration_ms   BIGINT      NOT NULL DEFAULT 0,
    finding_count INT         NOT NULL DEFAULT 0,
    error_message VARCHAR(512),
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_job_task ON scan_job (scan_task_id);

CREATE TABLE scan_execution_log (
    id           BIGSERIAL PRIMARY KEY,
    scan_task_id BIGINT      NOT NULL,
    stage        VARCHAR(32) NOT NULL,
    level        VARCHAR(8)  NOT NULL DEFAULT 'INFO',
    message      TEXT,
    created_at   TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_log_task ON scan_execution_log (scan_task_id);

CREATE TABLE compliance_evaluation (
    id           BIGSERIAL PRIMARY KEY,
    scan_task_id BIGINT      NOT NULL,
    project_id   BIGINT      NOT NULL,
    total_items  INT         NOT NULL DEFAULT 0,
    passed       INT         NOT NULL DEFAULT 0,
    failed       INT         NOT NULL DEFAULT 0,
    warning      INT         NOT NULL DEFAULT 0,
    manual       INT         NOT NULL DEFAULT 0,
    skipped      INT         NOT NULL DEFAULT 0,
    score        NUMERIC(5,2),
    created_at   TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_eval_task ON compliance_evaluation (scan_task_id);

CREATE TABLE checklist_item_result (
    id                  BIGSERIAL PRIMARY KEY,
    evaluation_id       BIGINT      NOT NULL,
    item_code           VARCHAR(64) NOT NULL,
    result              VARCHAR(16) NOT NULL,
    finding_count       INT         NOT NULL DEFAULT 0,
    matched_finding_ids JSONB,
    created_at          TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_cir_eval ON checklist_item_result (evaluation_id);
