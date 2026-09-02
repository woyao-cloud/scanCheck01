CREATE TABLE compliance_standard (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE compliance_checklist (
    id          BIGSERIAL PRIMARY KEY,
    standard_id BIGINT       NOT NULL,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE checklist_version (
    id               BIGSERIAL PRIMARY KEY,
    checklist_id     BIGINT       NOT NULL,
    version_no       VARCHAR(32)  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    content_snapshot JSONB,
    published_at     TIMESTAMP,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_cv_checklist ON checklist_version (checklist_id, version_no);

CREATE TABLE checklist_item (
    id           BIGSERIAL PRIMARY KEY,
    version_id   BIGINT       NOT NULL,
    item_code    VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    category     VARCHAR(64),
    risk_level   VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM',
    description  TEXT,
    basis        TEXT,
    remediation  TEXT,
    required     BOOLEAN      NOT NULL DEFAULT TRUE,
    waivable     BOOLEAN      NOT NULL DEFAULT FALSE,
    score_weight NUMERIC(6,3) NOT NULL DEFAULT 1.0,
    effective_from TIMESTAMP,
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_ci_version ON checklist_item (version_id, item_code);

CREATE TABLE checklist_item_detail (
    id          BIGSERIAL PRIMARY KEY,
    item_id     BIGINT NOT NULL,
    detail_json JSONB,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE project_checklist_binding (
    id                  BIGSERIAL PRIMARY KEY,
    project_id          BIGINT NOT NULL,
    checklist_version_id BIGINT NOT NULL,
    bound_at            TIMESTAMP NOT NULL DEFAULT now(),
    bound_by            BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_binding_project ON project_checklist_binding (project_id);
