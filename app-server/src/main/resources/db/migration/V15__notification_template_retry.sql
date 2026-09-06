-- M18: 通知模板（版本化，镜像 report_template 先例）+ 通知表补重试/Webhook payload 列
CREATE TABLE notification_template (
    id            BIGSERIAL PRIMARY KEY,
    template_type VARCHAR(32)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   TEXT,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_notification_template_type ON notification_template(template_type);

CREATE TABLE notification_template_version (
    id          BIGSERIAL PRIMARY KEY,
    template_id BIGINT       NOT NULL REFERENCES notification_template(id),
    version_no  INT          NOT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    content     JSONB        NOT NULL,
    created_by  BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (template_id, version_no)
);
CREATE INDEX idx_ntv_template ON notification_template_version (template_id, version_no);

ALTER TABLE notification ADD COLUMN recipient_ids TEXT;
ALTER TABLE notification ADD COLUMN occurred_at  TIMESTAMP;
ALTER TABLE notification ADD COLUMN next_retry_at TIMESTAMP;

-- 播种 6 类型默认 PUBLISHED 模板（version_no=1，内容=M17 现有硬编码标题/正文）
INSERT INTO notification_template (template_type, name) VALUES
    ('SCAN_COMPLETED',              'scan completed'),
    ('REPORT_SNAPSHOT_GENERATED',   'report snapshot generated'),
    ('REMEDIATION_ASSIGNED',        'remediation assigned'),
    ('REMEDIATION_COMPLETED',       'remediation completed'),
    ('FINDING_REGRESSION',          'finding regressed'),
    ('REMEDIATION_WAIVER',          'finding waived');

INSERT INTO notification_template_version (template_id, version_no, status, content) VALUES
    ((SELECT id FROM notification_template WHERE template_type = 'SCAN_COMPLETED'), 1, 'PUBLISHED',
     '{"title":"scan completed","body":"扫描 {scanTaskId} 完成：{status}"}'),
    ((SELECT id FROM notification_template WHERE template_type = 'REPORT_SNAPSHOT_GENERATED'), 1, 'PUBLISHED',
     '{"title":"report snapshot generated","body":"快照 {snapshotId} 已生成（{snapshotType}）"}'),
    ((SELECT id FROM notification_template WHERE template_type = 'REMEDIATION_ASSIGNED'), 1, 'PUBLISHED',
     '{"title":"remediation assigned","body":"finding {findingId} 已指派给你"}'),
    ((SELECT id FROM notification_template WHERE template_type = 'REMEDIATION_COMPLETED'), 1, 'PUBLISHED',
     '{"title":"remediation completed","body":"finding {findingId} 已标记完成"}'),
    ((SELECT id FROM notification_template WHERE template_type = 'FINDING_REGRESSION'), 1, 'PUBLISHED',
     '{"title":"finding regressed","body":"回归：{findingCount} 个 finding 在扫描 {scanTaskId} 复现"}'),
    ((SELECT id FROM notification_template WHERE template_type = 'REMEDIATION_WAIVER'), 1, 'PUBLISHED',
     '{"title":"finding waived","body":"finding {findingId} 被豁免（{reason}）"}');
