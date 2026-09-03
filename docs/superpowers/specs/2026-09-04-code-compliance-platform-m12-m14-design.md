# 代码合规扫描平台 第三阶段（M12–M14）整体规划设计

> 本文档是 M12/M13/M14 三个里程碑的整体规划（路线图），锁定各里程碑的目标、范围、关键设计决策与依赖顺序。每个里程碑关闭时另行出具实施计划，按序交付、逐里程碑确认。

**基线 spec：** `docs/superpowers/specs/2026-09-02-code-compliance-platform-design.md`（全局约束/枚举/模块划分/安全红线继续约束本阶段）
**既有阶段 spec：** `docs/superpowers/specs/2026-09-03-code-compliance-platform-phase2-design.md`（M6–M9）、`2026-09-03-code-compliance-platform-m11-design.md`（多引擎集成，已交付并 push）

---

## 1. 目标与范围

M0–M11 已交付：垂直链路（M0–M5）、整改闭环 + 版本化 + 五方法 Adapter 契约 + RBAC/三模块（M6–M9）、通知升级（M10）、多引擎集成 Gitleaks+Trivy + 依赖类 Finding 模型（M11）。

本阶段按依赖序推进三个增量：

| 里程碑 | 主题 | 目标 |
|---|---|---|
| **M12** | 合规报告快照 + 报告模板（版本化） | 补上平台「合规分析报告」核心目标：模板版本化 + 报告快照落库 + 模板驱动生成 + 导出（JSON/HTML）。基线 §10 明示「报告快照/模板后续里程碑另行设计」；架构原则 #5「报告模板必须支持版本化」、#10「报表和合规分析必须基于统一指标模型」。 |
| **M13** | 引擎收尾（健壮性 + 真实 E2E） | 真实二进制 E2E（门控）、SemgrepCli 诊断对齐、四引擎共享 CliExecutor 抽取、复扫依赖元数据刷新、依赖分流单边守卫。收尾 M11 终审遗留项。 |
| **M14** | 更多引擎（Dependency-Check + Detekt） | 依赖类引擎 Dependency-Check（复用 M11 依赖模型）+ 代码类引擎 Detekt（镜像 Semgrep）。基于已验证的 Adapter 五方法形态，机械扩展。SonarQube 仍需外部服务，保持延后。 |

### 1.1 非目标（延续基线 §1.3 / phase2 §1.1，仍延后）

- 通用报告模板引擎 / 自定义报表编排、PDF 导出（需重型库）
- SonarQube 引擎（依赖外部服务器）、真实二进制 E2E 纳入 CI 强制门禁（本阶段仅提供门控的可选本地运行）
- 企微/钉钉/飞书通知渠道、定时扫描/Webhook
- 质量门禁（构建红线）、豁免审批流
- ClickHouse 大规模分析、Elasticsearch、MinIO/S3（原始 JSON 仍存 PostgreSQL JSONB）
- 多租户与组织层级、AI 修复建议

---

## 2. 决策记录（本阶段新增/变更）

| 编号 | 问题 | 决策 |
|---|---|---|
| **P3-D1** | 里程碑划分与顺序 | M12→M13→M14 三个增量（用户已确认整体路线）。M12 独立（module-report + V13 迁移，无外部依赖）可先行；M13 独立（engine-adapter + module-result）；M14 依赖 M13 的「依赖分流单边守卫」前置（Dependency-Check 只设 cveId 时不踩 NPE 地雷）。 |
| **P3-D2** | 报告模板粒度（M12） | **固定报告类型 + 模板控制版本与展示**：报告类型枚举 `SCAN_SUMMARY / COMPLIANCE / TREND`，模板描述该类型的章节结构与展示配置，不引入通用模板引擎/自定义编排（YAGNI）。模板版本化镜像 checklist_version 先例（`report_template` + `report_template_version` 子表，status DRAFT/PUBLISHED/DISABLED，`VersionStatus` 枚举复用）。 |
| **P3-D3** | 报告快照 vs 实时重算（M12） | **快照落库，不可变**：`report_snapshot` 表按（模板版本 + 项目/任务 + 生成时刻）固化指标数据（JSONB 存快照负载），引用 `checklistVersionId`/`scanTaskId` 保证可追溯。合规审计要求报告反映生成时刻状态、可复现；历史扫描结果不可修改红线延伸至报告快照（只增不改不删）。 |
| **P3-D4** | 统一指标口径（M12） | 快照数据一律经 `ReportMetrics`（module-report 现成统一口径模型）聚合后落 JSONB；任何报表/快照不自行造口径。 |
| **P3-D5** | 导出格式（M12） | 首发 **JSON + HTML**（模板渲染的可读形式，快照数据直接序列化；HTML 由固定模板渲染，不引入重型库）。CSV 可选，PDF 延后。基线 §11「报告下载需鉴权」——导出端点走 JWT。 |
| **P3-D6** | 真实引擎 E2E（M13） | 环境门控测试：`@EnabledIfEnvironmentVariable`（或 system property `app.scan.e2e-engines`）控制，本机安装 gitleaks/trivy 二进制时运行真实扫描；CI/测试默认不装二进制（延续 spec 约束「CI/测试不要求安装引擎二进制」）。真实 E2E 不进 CI 强制门禁。 |
| **P3-D7** | 复扫依赖元数据刷新（M13） | `upsertByFingerprint` 的 REAPPEARED 分支：依赖类 finding（packageName/cveId 非空）在 bump `occurrenceCount`/`lastSeenAt` 的同时**刷新 `packageVersion`/`fixedVersion`/`cvssScore`**。不碰 `finding.status`（P2-D4 状态权威不变），只刷整改指导元数据。修复 advisory 更新后 fixedVersion 陈旧问题（M11 终审 Minor #2）。 |
| **P3-D8** | 依赖分流单边守卫（M13，M14 前置） | `upsertByFingerprint` 依赖类判断从 `packageName != null \|\| cveId != null` 收紧为 **`packageName != null && cveId != null`**；单边（仅其一非空）抛 `IllegalArgumentException` 带清晰消息——适配器契约保证 both-or-neither（Trivy 恒两者同设、Gitleaks 恒两者不设），单边是上游 bug，显式失败优于 NPE（M11 终审 Minor #1 硬化）。M14 Dependency-Check 实现必须遵守 both-or-neither。 |
| **P3-D9** | CliExecutor 抽取（M13） | 三个 `Process*Cli`（Semgrep/Gitleaks/Trivy）抽取共享进程执行器：超时、stdout/stderr 独立重定向、退出码语义、失败时 tail 诊断。**参数化**差异（Semgrep 用 redirectErrorStream+单文件、exit>=2 判定；Gitleaks/Trivy 用双文件、exit 0/1 或 0 判定、JSON 来源 report 文件/stdout）。抽取为纯重构，不改变任何 CLI 对外语义；既有 30 适配器测试必须全绿。 |
| **P3-D10** | 更多引擎注册（M14） | 延续 M11：`app.scan.checkout-engines` 配置扩展（加 DEPENDENCYCHECK、DETEKT，STUB* 仍排除 → commitId null）；EngineAdapterRegistry 自动收集 `ScanEngineAdapter` bean 注册，零框架改动。 |

---

## 3. M12 — 合规报告快照 + 报告模板（版本化）

### 3.1 数据模型（V13 迁移）

```sql
-- 报告模板（版本化，镜像 checklist_version 先例）
CREATE TABLE report_template (
    id          BIGSERIAL PRIMARY KEY,
    template_type VARCHAR(32) NOT NULL,          -- SCAN_SUMMARY / COMPLIANCE / TREND（唯一：每类型一条当前模板线）
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    version     BIGINT NOT NULL DEFAULT 1,       -- 版本表冗余列（镜像 checklist.version 模式）
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_report_template_type ON report_template(template_type);

CREATE TABLE report_template_version (
    id          BIGSERIAL PRIMARY KEY,
    template_id BIGINT NOT NULL REFERENCES report_template(id),
    version_no  INT NOT NULL,
    status      VARCHAR(32) NOT NULL,            -- VersionStatus: DRAFT / PUBLISHED / DISABLED
    sections    JSONB NOT NULL,                  -- 章节与展示配置（模板负载，如 {"sections":[{...}]}）
    created_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (template_id, version_no)
);

-- 报告快照（不可变，只增不改不删）
CREATE TABLE report_snapshot (
    id              BIGSERIAL PRIMARY KEY,
    template_id     BIGINT NOT NULL REFERENCES report_template(id),
    template_version_no INT NOT NULL,            -- 生成所用的模板版本（可追溯）
    project_id      BIGINT,                      -- 项目维度（COMPLIANCE/TREND）
    scan_task_id    BIGINT,                      -- 任务维度（SCAN_SUMMARY），可空
    checklist_version_id BIGINT,                 -- 关联清单版本（可追溯）
    snapshot_type   VARCHAR(32) NOT NULL,        -- SCAN_SUMMARY / COMPLIANCE / TREND
    payload         JSONB NOT NULL,              -- 经 ReportMetrics 聚合后的指标数据
    generated_by    BIGINT,
    generated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_report_snapshot_project ON report_snapshot(project_id);
CREATE INDEX idx_report_snapshot_task ON report_snapshot(scan_task_id);
```

### 3.2 生成流程

模板驱动生成：`ReportGenerationService` 读取该类型 **PUBLISHED 模板最新版** → 调 `ReportService` 既有查询（scanSummary/complianceSummary/trend 数据源）→ 经 `ReportMetrics` 统一口径聚合 → 序列化 payload → 落 `report_snapshot`（记模板版本号 + checklistVersionId + scanTaskId）。快照生成后不可变。

模板发布管理镜像 checklist：DRAFT 编辑 → PUBLISH 生效（生成只取 PUBLISHED）→ DISABLED 停用。

### 3.3 API（`/api/v1/reports` 扩展）

| 方法 | 路径 | 说明 | 角色 |
|---|---|---|---|
| POST | `/api/v1/reports/templates/{type}/draft` | 建/更新该类型模板 DRAFT 版本 | ADMIN / COMPLIANCE_MANAGER |
| POST | `/api/v1/reports/templates/{type}/publish` | 发布 DRAFT 为 PUBLISHED | ADMIN / COMPLIANCE_MANAGER |
| POST | `/api/v1/reports/templates/{type}/disable` | 禁用 | ADMIN |
| GET | `/api/v1/reports/templates/{type}/versions` | 模板全部版本（版本化可审计） | ADMIN / COMPLIANCE_MANAGER / AUDITOR |
| POST | `/api/v1/reports/{type}/generate` | 按类型生成快照（SCAN_SUMMARY 需 scanTaskId；COMPLIANCE/TREND 需 projectId） | 认证用户 |
| GET | `/api/v1/reports/snapshots?projectId=&type=` | 快照列表（分页） | 认证用户（项目数据权限） |
| GET | `/api/v1/reports/snapshots/{id}` | 快照详情 | 认证用户 |
| GET | `/api/v1/reports/snapshots/{id}/export?format=json\|html` | 导出（鉴权；JSON 序列化 payload / HTML 模板渲染） | 认证用户 |

### 3.4 测试

- `ReportTemplate` 版本状态机测试（DRAFT→PUBLISHED→DISABLED）、模板加载取 PUBLISHED 最新版
- `ReportSnapshot` 生成测试（三类型各一：payload 与 ReportService 查询口径一致——验收「报表数据与扫描结果一致」）
- 快照不可变测试（生成后不可改、不可删，只增）
- MockMvc 模板管理 + 生成 + 导出（`@WithMockUser`，Ruling #49）；export HTML 渲染快照
- V13 迁移在 `ddl-auto: validate` 下通过

---

## 4. M13 — 引擎收尾

### 4.1 真实二进制 E2E（门控）

`app-server/src/test` 新增 `RealEngineE2ETest`（或按引擎拆 gitleaks/trivy 两个类），`@EnabledIfEnvironmentVariable(name="APP_SCAN_E2E", matches="true")` 门控：

- 需要本机安装 gitleaks/trivy 二进制（spec 约束保留：CI 默认不装，测试类默认跳过）
- 门控内跑真扫描：`ScanOrchestrator` 全链路（checkout 到临时 workdir → 真实 adapter → finding 落库），对**注入的已知漏洞 fixture 目录**断言命中
- 复扫依赖元数据刷新（P3-D7）在真实 Trivy 二扫下验证 fixedVersion 刷新

### 4.2 SemgrepCli 诊断对齐 + CliExecutor 抽取

- `SemgrepCli` 失败（exit>=2 / timeout）抛异常补 stderr tail（对齐 Gitleaks/Trivy 的 `tailOf` 诊断；M11 终审 Important #1 覆盖 Semgrep）
- 抽取 `CliExecutor`（module-engine-adapter 内私有，或 module-common 工具）：参数化超时、stdout/stderr 重定向模式、成功退出码判定、JSON 来源（report 文件/stdout 文件）、失败 tail 诊断。三个 Process*Cli 改写为薄壳（P3-D9）。**纯重构**：既有 30 适配器测试 + 全量 build 必须全绿

### 4.3 复扫依赖元数据刷新（P3-D7）

`FindingService.upsertByFingerprint` REAPPEARED 分支：

```kotlin
existing.occurrenceCount += 1
existing.lastSeenAt = Instant.now()
// M13：依赖类 finding 刷新整改指导元数据（advisory 更新后 fixedVersion/cvss 不再陈旧）
if (existing.packageName != null && existing.cveId != null) {
    existing.packageVersion = f.packageVersion
    existing.fixedVersion = f.fixedVersion
    existing.cvssScore = f.cvssScore?.toBigDecimal()
}
```

不碰 `finding.status`（P2-D4 状态权威不变）；`FindingServiceTest` 补 REAPPEARED 依赖字段刷新测试。

### 4.4 依赖分流单边守卫（P3-D8，M14 前置）

`upsertByFingerprint` 分流条件收紧为 `&&`；单边抛 `IllegalArgumentException("dependency finding requires both packageName and cveId, got: ...")`。`FindingServiceTest` 补单边异常测试。

---

## 5. M14 — 更多引擎

### 5.1 Dependency-Check（OWASP，依赖类）

- **CLI**：`dependency-check --project <scanTaskId> --scan <target> --format JSON --out <file> --noupdate`（`--noupdate` 避免每次联网拉 NVD；`timeout-seconds` 配 `app.dependencycheck.timeout-seconds`，默认 600）
- **解析**（`dependency-check-report.json` → `dependencies[].vulnerabilities[]`）：engineRuleId=cveId=VulnerabilityId、filePath=scan 目标路径（锁文件不适用——DC 扫整个 tree，filePath 取文件路径字段或目标根，保持 NOT NULL）、severity 原生透传（HIGH/MEDIUM/LOW 直通 else→MEDIUM）、message=Description、packageName=包名（DC 用 `name`/`packages` 推断）、packageVersion=版本、fixedVersion（DC 无直接修复版本字段——`vulnerability` 无 fixedVersion，置 null）、cvssScore=CVSSv3.score 或 CVSSv2 兜底
- **契约**：五方法镜像 TrivyAdapter；engine="DEPENDENCYCHECK"；collectResult 保留原生 severity，normalizeResult 经 SeverityMapper（直通 else→MEDIUM）
- **both-or-neither 保证**：DC 每个漏洞恒设 packageName+cveId（P3-D8 守卫下安全）
- **checkout-engines** 加 `DEPENDENCYCHECK`

### 5.2 Detekt（Kotlin 静态分析，代码类）

- **CLI**：`detekt --input <target> --report sarif:<file>`（SARIF JSON 解析，镜像 Semgrep 代码类形态）
- **解析**（SARIF `runs[].results[]`）：engineRuleId=ruleId、filePath=locations[0].physicalLocation.artifactLocation.uri、line=region.startLine、severity 原生（error/warning 等 → 直通，normalize 经 SeverityMapper）、message=message.text、category=ruleId 前缀包名段
- **契约**：五方法镜像 SemgrepAdapter；engine="DETEKT"；代码类指纹（generate）——Detekt 命中是代码类，走既有代码类分流，不落依赖字段
- **checkout-engines** 加 `DETEKT`

### 5.3 集成测试

- M14 集成测试镜像 M11（STUBDC/STUBDET 桩适配器，setEvaluationPolicy FAIL、50×200ms poll、commitId null、`M14-*` 数据前缀）：Dependency-Check 依赖 finding 端到端（5 字段断言）；Detekt 代码类 finding 端到端
- 各引擎 fixture + 解析器/映射器/适配器单测（镜像 Gitleaks/Trivy 三测试类形态）；`@AfterEach` 清理临时文件（R-M11-4 教训内建于新测试）
- 全量 build 绿

---

## 6. 顺序与依赖

```
M12（module-report + V13）────────┐  独立，可先行
M13（engine-adapter + module-result）│  独立；P3-D8 守卫是 M14 前置
M14（engine-adapter + app-server）──┘  依赖 M13 P3-D8
```

每里程碑独立 spec/plan 交付、独立 SDD 执行、独立 push 授权。M12/M13 无相互依赖可并行设计，但按用户确认顺序逐里程碑执行（不并行实现）。

---

## 7. 全局约束（本阶段隐式生效，逐字沿用基线 §3.1/§4.8/§11/§13 与 phase2 §2）

1. **模块依赖**：叶子模块只依赖 `module-common`，互不 import 实体；跨模块引用一律通过 ID 或接口。`module-report` 可依赖 result/scan 的接口与值类型（ReportService 现状），禁止 import `@Entity`。
2. **模块内分层**：api/application/domain/infrastructure；Controller 不写业务逻辑、不返回 Entity。
3. **统一 API**：响应 `{code:0,message:"success",data}`；分页 `{items,page,size,total}`；路径 `/api/v1/{module}/{resource}`。
4. **表约定**：业务表含 `id/created_at/updated_at`；版本表含 `version`；`audit_log` 只增不改不删；原始扫描 JSON 存 jsonb。
5. **枚举统一**：`VersionStatus=DRAFT/PUBLISHED/DISABLED`（M12 模板复用）；`FindingStatus` 11 态不变；severity 原生透传、仅 normalizeResult 映射。
6. **安全**：除 `login`/`swagger`/`/actuator/health`/CI 触发外全部 JWT；凭据 AES；敏感信息不写日志；报告下载需鉴权。
7. **红线**：不硬编码合规判定规则；不绕过 Adapter；历史扫描结果不可修改；`audit_log` 不删改。
8. **P2-D4**：`finding.status` 唯一权威；M13 元数据刷新不触碰状态转移。
9. **P2-D5**：跨模块只 import 接口/值类型，绝不 import `@Entity`。
10. **Ruling #45/#52**：编排器路径不添加 `@Transactional`。
11. **Ruling #49**：MockMvc HTTP 测试必须 `@WithMockUser`。
12. **checkout-engines 门控**：STUB* 不在列表 → commitId null；`EngineAdapterRegistry` 自动注册 bean。
13. **R-M11-1**：实体 `cvssScore` 持 `BigDecimal?` ↔ V12 `NUMERIC`，DTO 持 `Double?`，边界转换；V13 新增列同样 NUMERIC/BigDecimal。
14. **共享 Testcontainers**：所有 app-server 集成测试共享 PG 容器（`max_connections=300` 保持）；数据全局唯一前缀 `M12-*`/`M13-*`/`M14-*`；`SmokeFirstClassOrderer` 不变。
15. **全部 Gradle 命令用 `./gradlew`（wrapper 8.8）**。
