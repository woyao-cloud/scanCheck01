-- M11：依赖类字段（Trivy 依赖漏洞）。全部可空 —— 既有 finding 行不迁移、不填默认值。
-- cvss_score NUMERIC ↔ Finding.cvssScore BigDecimal（先例：checklist.score_weight、scan.score 均 NUMERIC↔BigDecimal）。
ALTER TABLE finding
    ADD COLUMN package_name    TEXT,
    ADD COLUMN package_version TEXT,
    ADD COLUMN fixed_version   TEXT,
    ADD COLUMN cve_id          VARCHAR(64),
    ADD COLUMN cvss_score      NUMERIC;
