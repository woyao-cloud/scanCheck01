CREATE TABLE org_project (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(64)  NOT NULL UNIQUE,
    name          VARCHAR(128) NOT NULL,
    description   TEXT,
    owner_user_id BIGINT,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE repo_info (
    id             BIGSERIAL PRIMARY KEY,
    project_id     BIGINT       NOT NULL,
    name           VARCHAR(128) NOT NULL,
    git_url        VARCHAR(512) NOT NULL,
    provider       VARCHAR(32)  NOT NULL,
    default_branch VARCHAR(128) NOT NULL DEFAULT 'main',
    credential_ref VARCHAR(256),
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_repo_project ON repo_info (project_id);
