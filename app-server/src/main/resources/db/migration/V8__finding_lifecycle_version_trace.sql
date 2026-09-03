-- (1) finding：补 project_id（回填自 scan_task，随后 NOT NULL）
ALTER TABLE finding ADD COLUMN project_id BIGINT;
UPDATE finding f SET project_id = (SELECT t.project_id FROM scan_task t WHERE t.id = f.scan_task_id)
    WHERE f.project_id IS NULL;
ALTER TABLE finding ALTER COLUMN project_id SET NOT NULL;
CREATE INDEX idx_finding_project ON finding (project_id);

-- (2) 状态快照：每次状态转移写一行（finding.status 镜像最新）
CREATE TABLE finding_status (
    id         BIGSERIAL PRIMARY KEY,
    finding_id BIGINT      NOT NULL,
    status     VARCHAR(16) NOT NULL,
    changed_by BIGINT,
    changed_at TIMESTAMP   NOT NULL DEFAULT now(),
    reason     TEXT,
    created_at TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_finding_status_finding ON finding_status (finding_id, changed_at DESC);

-- (3) finding_trace 演进为 finding_history（扫描出现历史，只增不改）
ALTER TABLE finding_trace RENAME TO finding_history;
ALTER TABLE finding_history ADD COLUMN changed_by BIGINT;
ALTER TABLE finding_history ADD COLUMN detail TEXT;
-- PF-8：spec §3.1 要求 changed_at 与 (finding_id, changed_at DESC) 索引（基线 finding_trace 已含 changed_at 列，RENAME 保留；此处确保其存在）
ALTER TABLE finding_history ADD COLUMN IF NOT EXISTS changed_at TIMESTAMP NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_finding_history_finding ON finding_history (finding_id, changed_at DESC);
CREATE INDEX IF NOT EXISTS idx_finding_history_scan ON finding_history (scan_task_id, finding_id);

-- (4) 证据
CREATE TABLE finding_evidence (
    id            BIGSERIAL PRIMARY KEY,
    finding_id    BIGINT       NOT NULL,
    evidence_type VARCHAR(32)  NOT NULL,
    evidence_ref  VARCHAR(512) NOT NULL,
    added_by      BIGINT,
    added_at      TIMESTAMP    NOT NULL DEFAULT now(),
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_finding_evidence_finding ON finding_evidence (finding_id);

-- (5) scan_task：版本追溯 + 运行元数据
ALTER TABLE scan_task ADD COLUMN checklist_version_id BIGINT;
ALTER TABLE scan_task ADD COLUMN rule_ids JSONB;
ALTER TABLE scan_task ADD COLUMN commit_id VARCHAR(64);
ALTER TABLE scan_task ADD COLUMN duration_ms BIGINT;
ALTER TABLE scan_task ADD COLUMN request_id VARCHAR(64);

-- (6) 评估结果表补版本
ALTER TABLE checklist_item_result ADD COLUMN checklist_version_id BIGINT;
ALTER TABLE compliance_evaluation ADD COLUMN checklist_version_id BIGINT;

-- (7) finding.status 存量映射到 11 态（OPEN→NEW、REOPENED→CONFIRMED、SUPPRESSED→FALSE_POSITIVE）
UPDATE finding SET status = CASE
    WHEN status = 'OPEN'       THEN 'NEW'
    WHEN status = 'REOPENED'   THEN 'CONFIRMED'
    WHEN status = 'SUPPRESSED' THEN 'FALSE_POSITIVE'
    ELSE status
END;

-- (8) checklist 唯一约束（Minors 清理）
ALTER TABLE checklist_version ADD CONSTRAINT uq_checklist_version_no UNIQUE (checklist_id, version_no);
-- 注：V4 基线 checklist_item 的版本外键列名为 version_id（Task 6.1 简报引作 checklist_version_id，与实际 schema 不符），按实际列建约束，语义不变
ALTER TABLE checklist_item    ADD CONSTRAINT uq_item_code_version UNIQUE (version_id, item_code);
