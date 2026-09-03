-- CI 触发 API Token（P2-D7）：多 CI 各自一个 token，可独立禁用/过期；明文仅创建时返回一次
CREATE TABLE api_token (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(64)  NOT NULL UNIQUE,
    token_hash   VARCHAR(128) NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    expires_at   TIMESTAMP,
    last_used_at TIMESTAMP,
    created_by   BIGINT,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now()
);
-- PF-7：spec §6.3 不要求 status 索引（按 name 唯一查找 + 小表），不建 idx_api_token_status
