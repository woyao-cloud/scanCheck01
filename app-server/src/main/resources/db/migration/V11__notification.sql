CREATE TABLE notification (
    id          BIGSERIAL PRIMARY KEY,
    channel     VARCHAR(16)  NOT NULL,
    recipient   VARCHAR(128) NOT NULL,
    type        VARCHAR(32)  NOT NULL,
    title       VARCHAR(200) NOT NULL,
    content     TEXT,
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    retry_count INT          NOT NULL DEFAULT 0,
    sent_at     TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_status ON notification (status, channel);
CREATE INDEX idx_notification_recipient ON notification (recipient);
