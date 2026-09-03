-- 整改任务：status 为冗余缓存列（P2-D4，权威=finding.status，同事务镜像写入）
CREATE TABLE remediation_task (
    id               BIGSERIAL PRIMARY KEY,
    finding_id       BIGINT      NOT NULL,
    project_id       BIGINT      NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    assignee_user_id BIGINT,
    plan             TEXT,
    due_date         DATE,
    created_by       BIGINT,
    created_at       TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_remediation_finding  ON remediation_task (finding_id);
CREATE INDEX idx_remediation_project  ON remediation_task (project_id, status);
