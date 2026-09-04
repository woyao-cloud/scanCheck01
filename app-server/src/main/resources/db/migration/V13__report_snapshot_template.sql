-- M12: 报告模板（版本化，镜像 checklist_version 先例）+ 报告快照（不可变）
CREATE TABLE report_template (
    id            BIGSERIAL PRIMARY KEY,
    template_type VARCHAR(32)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   TEXT,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_report_template_type ON report_template(template_type);

CREATE TABLE report_template_version (
    id          BIGSERIAL PRIMARY KEY,
    template_id BIGINT       NOT NULL REFERENCES report_template(id),
    version_no  INT          NOT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    sections    JSONB        NOT NULL,
    created_by  BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (template_id, version_no)
);
CREATE INDEX idx_rtv_template ON report_template_version (template_id, version_no);

CREATE TABLE report_snapshot (
    id                  BIGSERIAL PRIMARY KEY,
    template_id         BIGINT       NOT NULL REFERENCES report_template(id),
    template_version_no INT          NOT NULL,
    project_id          BIGINT,
    scan_task_id        BIGINT,
    checklist_version_id BIGINT,
    snapshot_type       VARCHAR(32)  NOT NULL,
    payload             JSONB        NOT NULL,
    generated_by        BIGINT,
    generated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_report_snapshot_project ON report_snapshot(project_id);
CREATE INDEX idx_report_snapshot_task ON report_snapshot(scan_task_id);
