CREATE TABLE rule_definition (
    id          BIGSERIAL PRIMARY KEY,
    rule_code   VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    risk_level  VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM',
    status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    description TEXT,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE rule_engine_binding (
    id               BIGSERIAL PRIMARY KEY,
    rule_id          BIGINT       NOT NULL,
    engine           VARCHAR(32)  NOT NULL,
    engine_rule_id   VARCHAR(128) NOT NULL,
    engine_config_json JSONB,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_reb_engine ON rule_engine_binding (engine, engine_rule_id);

CREATE TABLE rule_compliance_mapping (
    id                 BIGSERIAL PRIMARY KEY,
    rule_id            BIGINT      NOT NULL,
    checklist_item_code VARCHAR(64) NOT NULL,
    created_at         TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_rcm_rule ON rule_compliance_mapping (rule_id, checklist_item_code);

CREATE TABLE rule_evaluation_policy (
    id               BIGSERIAL PRIMARY KEY,
    rule_id          BIGINT      NOT NULL UNIQUE,
    result_on_match  VARCHAR(16) NOT NULL DEFAULT 'FAIL',
    policy_json      JSONB,
    sp_el_expression TEXT,
    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP   NOT NULL DEFAULT now()
);
