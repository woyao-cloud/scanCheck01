# 代码合规扫描平台 — 设计文档

- 日期：2026-09-02
- 状态：已确认
- 依据：`plan.md`、`Desig.md`、`AGENTS.md`、`CLAUDE.md`
- 环境：Java 21、Gradle 8.x、Docker（本机已验证可用）

## 1. 目标与范围

建设一个基于 Kotlin 的**代码合规扫描平台**，统一管理代码扫描、合规清单配置、规则管理、扫描结果集成、合规分析报告与整改闭环。

### 1.1 本轮交付范围

本设计文档是本平台的可执行蓝图，配套实施计划文档将定义分步实现。**本轮（本阶段）只交付设计文档与实施计划，不写代码。**

### 1.2 首个建设里程碑（MVP 垂直链路）

实施阶段优先打通一条完整业务链（与 plan.md「阶段一 MVP」对应）：

```
项目/仓库管理 → 合规清单基础配置 → 规则基础管理
→ 创建扫描任务 → Semgrep Adapter 执行 → 标准化为统一 Finding
→ 指纹去重 → 映射合规项 → 合规判定 → 基础报表
```

首发引擎：**Semgrep Adapter**（解析 Semgrep JSON，fixture 离线测试，后续接真实引擎）。

### 1.3 非目标（本设计明确延后，YAGNI）

以下能力在 plan.md 中属于 P1/P2，**本设计与首个实施阶段不做**，仅预留扩展点：

- Redis 缓存 / RabbitMQ / Kafka（首发链路用 Spring Async + 数据库任务表）
- MinIO / S3、Elasticsearch、ClickHouse（原始 JSON 暂存 PostgreSQL JSONB）
- 多引擎真实集成（仅 Semgrep 一个 Adapter，框架可扩展）
- 豁免审批流、通知渠道（企业微信/钉钉/飞书）、质量门禁
- 自定义脚本规则、AI 修复建议、组织级看板、智能分析
- 定时扫描 / Webhook / CI 触发（首发仅手动触发，`module-openapi` 只留接口骨架）
- 多租户与组织层级（`Tenant`、`Organization`、`Application`、`Environment`）：首发按单租户 + `Project` 级管理，实体与数据模型预留扩展字段，后续再落地

---

## 2. 技术栈（具体版本）

| 类别 | 选型 | 说明 |
|---|---|---|
| 语言 | Kotlin 2.x（JDK 21） | 本机 Java 21 + Gradle 8.2.1 已验证 |
| 框架 | Spring Boot 3.3.x | 模块化单体 |
| Web | Spring MVC + springdoc-openapi | REST API 与文档 |
| 安全 | Spring Security + JWT | 无状态认证、RBAC |
| ORM | Spring Data JPA（Hibernate）+ Kotlin 适配 | 复杂查询后续引入 jOOQ |
| 迁移 | Flyway | 版本化迁移脚本 |
| 数据库 | PostgreSQL 16 | docker-compose 提供 |
| 校验 | Spring Validation | `jakarta.validation` |
| 表达式 | Spring Expression Language（SpEL） | 合规判定策略，不开放任意脚本 |
| 异步 | Spring `@Async` + `TaskExecutor` | 扫描任务异步执行 |
| 测试 | JUnit 5、MockK、Testcontainers、MockMvc | 单元 / 集成 / API |
| 日志 | Logback + SLF4J | 敏感信息脱敏约束 |

---

## 3. 工程结构与模块职责

多模块 Gradle，共 15 个子模块，与 plan.md「系统模块划分」一致：

```text
code-compliance-platform
├── app-server                  # 启动装配：@SpringBootApplication、全局配置、跨模块 Bean 扫描
├── module-common               # 公共能力：统一响应、分页、异常、审计基类、安全工具
├── module-auth                 # 认证授权：JWT、登录、Security 过滤器链
├── module-user                 # 用户组织：用户、角色、权限
├── module-project              # 项目应用：项目、代码仓库、分支、扫描配置
├── module-checklist            # 合规清单：标准、清单、合规项、版本、项目绑定
├── module-rule                 # 规则中心：规则、引擎绑定、合规映射、判定策略
├── module-scan                 # 扫描任务：任务/子任务、编排、调度、执行日志
├── module-engine-adapter       # 扫描引擎适配：ScanEngineAdapter 框架 + SemgrepAdapter
├── module-result               # 结果归一化：统一 Finding 模型、标准化、去重、聚合
├── module-report               # 报表报告：基础报表、趋势、导出接口
├── module-remediation          # 整改闭环：问题状态机、整改任务、审计（首发为骨架）
├── module-notification         # 通知服务：接口抽象 + 站内信占位（首发为骨架）
├── module-openapi              # 对外 API：CI/CD 触发接口（首发仅骨架）
└── module-admin                # 管理后台接口：汇聚 admin 侧查询/配置端点（首发仅骨架）
```

### 3.1 模块依赖规则（防止循环依赖）

- **叶子业务模块**（`user`、`project`、`checklist`、`rule`、`result`、`remediation`、`notification`、`openapi`、`admin`）只依赖 `module-common`，**叶子模块之间不互相 import 实体**，跨模块引用一律通过 **ID 或接口**。
- 唯一例外：`module-auth` → `module-user`（登录需查询用户与角色，单向无环），其余叶子模块保持互不依赖。
- `module-result` 定义统一 `Finding` 模型与标准化接口，仅依赖 `common`；`Finding` 通过 `projectId`、`scanTaskId`、`ruleId` 等普通 ID 关联其他模块，避免反向依赖。
- `module-engine-adapter` 依赖 `common` + `result`（面向接口上报），**禁止**依赖 `scan` 或任何业务模块。
- `module-scan` 是**编排层**（唯一允许依赖多个叶子模块者）：依赖 `common` + `project` + `checklist` + `rule` + `result` + `engine-adapter` 的接口。
- `module-report` 依赖 `common` + `result` + `scan` + `checklist` 的查询接口。
- `app-server` 依赖全部模块，负责装配与启动。

> 约定：任何模块之间的编译期依赖若形成环，通过抽出「接口模块」或改为「ID 关联」解决。

### 3.2 模块内分层（每个业务模块统一）

按 plan.md 5.3，每模块内部分层：

```text
<module>/
├── api/            # Controller、DTO、VO、Command、Query（只做协议转换）
├── application/    # Application Service：编排、事务、发事件
├── domain/         # 实体（data class）、枚举（enum class）、领域服务、端口接口
└── infrastructure/ # Repository 实现、JPA 实体映射、事件监听
```

职责边界执行 plan.md「分层规范」：Controller 不写业务逻辑、不返回 Entity；Repository 不写业务规则；领域规则集中在 domain。

---

## 4. 核心领域模型（Kotlin 视角）

所有实体用 `data class`，状态用 `enum class` / `sealed class`，优先不可变集合与 `val`。以下为核心模型及关键字段。

### 4.1 项目资产

- `Project`（org_project）：id、name、code、description、status(ACTIVE/ARCHIVED)、ownerUserId、createdAt、updatedAt
- `Repository`（repo_info）：id、projectId、name、gitUrl、provider(GITLAB/GITHUB/GITEA/BITBUCKET)、defaultBranch、credentialRef（加密后引用）、status

### 4.2 合规清单

- `ComplianceStandard`：id、code、name、description、status
- `ComplianceChecklist`：id、standardId、code、name、description、status
- `ChecklistItem`：id、checklistVersionId、itemCode、name、category、riskLevel(枚举)、description、basis、remediation、required(Boolean)、waivable(Boolean)、scoreWeight、effectiveFrom、version
- `ChecklistItemDetail`：id、itemId、detailJson（证据要求、责任人等扩展字段）
- `ChecklistVersion`：id、checklistId、versionNo、status(DRAFT/PUBLISHED/DISABLED)、publishedAt、contentSnapshot
- `ProjectChecklistBinding`：id、projectId、checklistVersionId、boundAt、boundBy

### 4.3 规则中心

- `RuleDefinition`：id、ruleCode、ruleName、ruleType、language、severity、description、remediation、status(DRAFT/TESTING/PUBLISHED/DISABLED)、version
- `RuleEngineBinding`：id、ruleId、engineType、engineRuleId、parameters(JSON)、timeoutSeconds、filePatterns、excludePatterns、retryPolicy
- `RuleComplianceMapping`：id、ruleId、checklistItemId、conclusionOnHit、scoreImpact
- `RuleEvaluationPolicy`：id、ruleId、metricType、operator、threshold、resultStatus、expression(SpEL)

### 4.4 扫描执行

- `ScanTask`（scan_task）：id、projectId、branch、commitId、triggerType(MANUAL/SCHEDULED/WEBHOOK/API/CI)、status(PENDING/PREPARING/RUNNING/SUCCESS/FAILED/CANCELLED/PARTIAL_SUCCESS)、checklistVersionId、ruleIds(JSON)、startedAt、finishedAt、durationMs、errorMessage、requestId
- `ScanJob`（scan_job）：id、scanTaskId、engineType、status、retryCount、maxRetry、executionLogRef
- `ScanExecutionLog`：id、scanTaskId、jobId、level、message、timestamp

### 4.5 统一扫描结果

`Finding`（finding，**平台唯一结果模型**）：

```text
id、findingId（业务编码）、projectId、scanTaskId、scanJobId、
engineType、ruleId、ruleCode、findingType、severity(枚举)、
title、description、filePath、lineNumber、codeSnippet、
packageName、packageVersion、fixedVersion、cveId、cvssScore、licenseId、
repository、branch、commitId、fingerprint、status、rawResult(JSONB)
```

- `FindingStatus`（finding_status）：状态快照表，记录 id、findingId、status、changedBy、changedAt、reason
- `FindingHistory`（finding_history）：状态历史（不可改，追加式）
- `FindingEvidence`：id、findingId、evidenceType、evidenceRef（证据引用）

### 4.6 合规评估

- `ChecklistItemResult`：id、scanTaskId、checklistVersionId、itemId、itemCode、resultStatus(PASS/WARNING/FAIL/MANUAL/SKIPPED)、matchedFindingIds(JSON)、evaluatedAt
- `ComplianceEvaluation`：id、scanTaskId、checklistVersionId、totalItems、passed、failed、warning、manual、skipped、passRate、score、conclusion、evaluatedAt

### 4.7 审计

- `AuditLog`：id、userId、action、module、resourceType、resourceId、detail(JSON)、ip、occurredAt（**不可物理删除**）
- `AuditLog` 实体与 `AuditService` 放在 **`module-common`**（跨模块横切能力），所有模块通过它记录敏感操作。

### 4.8 枚举（统一规范）

```text
Severity:    CRITICAL / HIGH / MEDIUM / LOW / INFO
TaskStatus:  PENDING / PREPARING / RUNNING / SUCCESS / FAILED / CANCELLED / PARTIAL_SUCCESS
ItemResult:  PASS / WARNING / FAIL / MANUAL / SKIPPED
RuleStatus:  DRAFT / TESTING / PUBLISHED / DISABLED
VersionStatus: DRAFT / PUBLISHED / DISABLED
FindingStatus: NEW / CONFIRMED / ASSIGNED / FIXING / FIXED / RECHECKING /
               CLOSED / IGNORED / FALSE_POSITIVE / ACCEPTED_RISK / WAIVED
```

---

## 5. 数据库设计

主库 PostgreSQL 16，Flyway 管理迁移。所有业务表含 `id`、`created_at`、`updated_at`；版本表含 `version`；审计表不可物理删除。原始扫描 JSON 存 `jsonb` 列（暂不引入对象存储）。

### 5.1 P0 核心表集（首发链路落地）

| 模块 | 表 | 说明 |
|---|---|---|
| auth/user | `sys_user`、`sys_role`、`sys_permission`、`sys_user_role`、`sys_role_permission` | 用户/角色/权限 RBAC |
| project | `org_project`、`repo_info` | 项目与仓库 |
| checklist | `compliance_standard`、`compliance_checklist`、`checklist_version`、`checklist_item`、`checklist_item_detail`、`project_checklist_binding` | 标准→清单→版本→合规项→绑定 |
| rule | `rule_definition`、`rule_engine_binding`、`rule_compliance_mapping`、`rule_evaluation_policy` | 规则与映射 |
| scan | `scan_task`、`scan_job`、`scan_execution_log`、`compliance_evaluation`、`checklist_item_result` | 任务编排与合规评估（评估器在 scan 流水线内执行，写入评估结果表） |
| result | `finding`、`finding_status`、`finding_history`、`finding_evidence` | 统一结果与状态 |
| report | 无独立表（报表为聚合查询；报告快照/模板后续里程碑另行设计） | 基础报表 |
| common | `audit_log` | 审计（横切，实体与 AuditService 在 common） |

### 5.2 关键字段约定

- 所有 `id`：`bigserial`（主键）+ 业务编码字段（如 `rule_code`）保证可读性。
- 状态枚举存 `varchar`，附 CHECK 约束或由应用层约束。
- `parameters`、`detailJson`、`matchedFindingIds`、`rawResult` 等动态结构用 `jsonb`。
- 索引要点：
  - `finding(project_id, scan_task_id)`、`finding(fingerprint)`（去重查询）
  - `finding_status(finding_id, changed_at desc)`（取最新状态）
  - `checklist_item_result(scan_task_id, item_id)`
  - `scan_task(project_id, created_at desc)`
- 高频统计字段（如 `finding` 上的 `status`、`severity`）冗余在结果行，报表按需聚合，暂不引入 ClickHouse。

### 5.3 数据保留与安全

- 审计日志只增不改不删；业务删除一律逻辑删除（`deleted` 标记），后续按需归档。

---

## 6. API 设计

统一约定（沿用 plan.md）：

- 路径：`/api/v1/{module}/{resource}`
- 响应：`{ "code": 0, "message": "success", "data": {} }`
- 分页请求：`?page=1&size=20&sort=createdAt,desc`；分页响应：`data: { items, page, size, total }`
- 认证：除登录外全部要求 JWT；`module-openapi` 用独立 API Token

### 6.1 端点清单（首发实现）

| 模块 | 端点 |
|---|---|
| auth | `POST /api/v1/auth/login`、`POST /api/v1/auth/logout`、`GET /api/v1/auth/me` |
| user | `GET/POST/PUT /api/v1/users`、`GET /api/v1/users/{id}/roles` |
| project | `GET/POST /api/v1/projects`、`GET/PUT /api/v1/projects/{id}`、`POST /api/v1/projects/{id}/repositories`、`GET /api/v1/projects/{id}/repositories` |
| checklist | `GET/POST /api/v1/compliance/standards`、`GET/POST /api/v1/compliance/checklists`、`GET /api/v1/compliance/checklists/{id}/versions`、`POST /api/v1/compliance/checklists/{id}/versions`、`POST /api/v1/compliance/checklists/{id}/publish`、`POST /api/v1/projects/{projectId}/bind-checklist`、`GET /api/v1/projects/{projectId}/checklists` |
| rule | `GET/POST /api/v1/rules`、`PUT /api/v1/rules/{id}`、`POST /api/v1/rules/{id}/publish`、`POST /api/v1/rules/{id}/disable`、`GET /api/v1/rules/{id}/versions` |
| scan | `POST /api/v1/projects/{projectId}/scan-tasks`、`GET /api/v1/scan-tasks/{taskId}`、`POST /api/v1/scan-tasks/{taskId}/cancel`、`GET /api/v1/scan-tasks/{taskId}/findings`、`GET /api/v1/scan-tasks/{taskId}/compliance-results` |
| report | `GET /api/v1/reports/scan-summary`、`GET /api/v1/reports/compliance-summary`、`GET /api/v1/reports/trend` |

`module-openapi`、`module-admin`、`module-notification`、`module-remediation` 首发仅提供骨架端点（占位 + 403 提示），不实现业务。

---

## 7. Semgrep Adapter 设计

### 7.1 适配器契约

```text
interface ScanEngineAdapter {
    fun supports(engineType: EngineType): Boolean
    fun prepareScan(context: ScanContext)
    fun executeScan(context: ScanContext)
    fun collectResult(context: ScanContext)
    fun normalizeResult(context: ScanContext): List<Finding>
    fun cleanup(context: ScanContext)
}
```

- `module-result` 定义 `ScanContext`（含工作目录、规则参数、超时）与 `Finding` 模型。
- `module-engine-adapter` 注册各实现，`module-scan` 通过 `EngineAdapterRegistry` 按 `EngineType` 分发。

### 7.2 SemgrepAdapter 行为

- 输入：Semgrep `--json` 输出（含 `results[]`、`rules[]`、`errors[]`）。
- 映射：
  - `check_id` → `ruleCode`；经 `rule_engine_binding` 关联平台 `ruleId`
  - `path`/`start.line`/`extra.lines` → `filePath`/`lineNumber`/`codeSnippet`
  - `extra.message`/`extra.metadata` → `title`/`description`
- 严重等级映射表（Semgrep → 平台统一，默认值；映射可配置，后续支持按项目/清单覆盖）：

| Semgrep | 平台（默认） |
|---|---|
| ERROR | HIGH |
| WARNING | MEDIUM |
| INFO | LOW |

> 平台统一等级不允许引擎自定义直接进入业务层，必须先经映射（plan.md 要求）。
- 错误与超时：引擎进程失败/超时记为 `ScanExecutionLog`，任务按 `retryPolicy` 重试。
- fixture 测试：`src/test/resources/fixtures/semgrep/*.json` 提供样例输出，验证解析、映射、fingerprint 生成。

### 7.3 去重指纹

```text
代码类：  fingerprint = sha256(projectId + ruleCode + filePath + lineNumber + codeSnippet)
依赖类：  fingerprint = sha256(projectId + dependency + version + cveId)   # 预留
许可证类：fingerprint = sha256(projectId + dependency + licenseId)         # 预留
```

同一指纹在同一 `scan_task` 内只保留一条（引擎多报去重），跨任务按状态机复用（NEW 命中历史已修复则跳过）。

---

## 8. 扫描主流程（时序）

```text
POST /projects/{id}/scan-tasks
  → ScanTask 落库 PENDING
  → 异步 Orchestrator:
      1. 加载项目 + 仓库配置
      2. 加载绑定合规清单版本 + 生效规则集
      3. 任务置 PREPARING，创建 ScanJob（每引擎一个）
      4. 调 EngineAdapterRegistry 取 SemgrepAdapter
      5. prepareScan → executeScan → collectResult → normalizeResult
      6. 结果标准化为 Finding，生成 fingerprint 去重
      7. 任务置 RUNNING/SUCCESS/FAILED/PARTIAL_SUCCESS，记录耗时与日志
  → 合规评估（ComplianceEvaluator）:
      1. Finding 按 ruleCode 映射合规项（rule_compliance_mapping）
      2. 按 ChecklistItemResult 逐项判定（结构化策略 + SpEL）
      3. 汇总 ComplianceEvaluation：passRate、score、conclusion
  → 报表查询可见结果
```

- 幂等：同一 `projectId + branch` 已有 RUNNING/PENDING 任务时拒绝重复创建（唯一约束 + 任务锁）。
- 取消：置 CANCELLED，中断未完成 Job。
- 历史扫描结果不可修改；整改后重新扫描产生新任务。

---

## 9. 合规判定与评估

- 判定策略采用**结构化配置 + SpEL 表达式**，禁止在业务代码硬编码合规规则（plan.md 硬性要求）。
- 策略示例（`rule_evaluation_policy` 结构化字段）：

```text
metricType=HIGH_COUNT operator=GREATER_THAN threshold=0 resultStatus=FAIL
metricType=MEDIUM_COUNT operator=GREATER_THAN threshold=10 resultStatus=WARNING
```

- 表达式示例（复杂场景）：`#highCount > 0 ? 'FAIL' : (#mediumCount > 10 ? 'WARNING' : 'PASS')`
- 默认规则（未配置策略时）：命中任何 `HIGH/CRITICAL` → FAIL；命中 `MEDIUM` → WARNING；否则 PASS。
- 合规项结果状态：`PASS / WARNING / FAIL / MANUAL / SKIPPED`。
- 评估与报告必须绑定 `checklistVersionId` 与规则版本，保证可追溯（plan.md 要求）。

---

## 10. 报表（首发基础）

- `GET /api/v1/reports/scan-summary`：按任务汇总（问题总数、各严重级计数、去重后计数）
- `GET /api/v1/reports/compliance-summary`：按清单版本汇总（通过率、评分、结论、各项明细）
- `GET /api/v1/reports/trend`：按项目按日期的趋势（新增/关闭问题数）
- 数据源：PostgreSQL 聚合查询；字段与扫描结果口径一致（验收要求：报表数据与扫描结果一致）
- 导出（Excel/PDF/HTML）列入后续里程碑，首发仅 JSON。

---

## 11. 安全设计

- 认证：Spring Security + JWT（`/api/v1/auth/login` 匿名，其余需认证）。
- 授权：RBAC，角色 `ADMIN / COMPLIANCE_MANAGER / PROJECT_OWNER / DEVELOPER / AUDITOR`，数据权限按项目隔离（首发按角色粗粒度，细粒度数据权限后续）。
- 密码：BCrypt 加密存储。
- 仓库凭据：加密存储（应用级 AES），日志脱敏。
- 审计：`module-common` 提供 `AuditService`，各模块对敏感操作调用；审计日志只增不改不删。
- 安全红线：不允许密钥/Token 写入日志；异常统一处理不泄露系统细节；报告下载需鉴权。

---

## 12. 测试策略

| 层次 | 工具 | 覆盖 |
|---|---|---|
| 单元 | JUnit 5 + MockK | 判定策略、评分、Finding 标准化、Adapter 转换、fingerprint |
| 集成 | Testcontainers PostgreSQL | Flyway 迁移、Repository 查询、任务编排（真实 DB） |
| API | MockMvc | 参数校验、权限、统一响应格式 |
| 适配 | fixture JSON | Semgrep 解析/映射/去重（离线，不依赖本机 semgrep） |

- 每模块核心业务路径必须有测试；扫描流程集成测试走内存队列 + 同步执行模式（测试 profile 关闭真实异步）。

---

## 13. 错误处理与统一响应

- 统一异常体系：`BusinessException`（code/message）→ 全局 `@RestControllerAdvice` 转统一响应。
- 参数校验：Spring Validation，错误信息聚合返回。
- 引擎错误：捕获后转任务级错误 + `ScanExecutionLog`，不中断整批其他 Job（PARTIAL_SUCCESS 语义）。

---

## 14. 里程碑与构建顺序（实施计划的输入）

| 里程碑 | 内容 | 完成标准 |
|---|---|---|
| M0 | 多模块 Gradle 骨架 + `module-common`（统一响应/异常/分页）+ 全局配置 + Docker Compose + Flyway 基础 | 编译通过，可启动空应用 |
| M1 | `module-auth` + `module-user`（JWT 登录、RBAC、用户/角色） | 登录/鉴权测试通过 |
| M2 | `module-project`（项目/仓库 CRUD + 加密凭据） | CRUD + 校验测试通过 |
| M3 | `module-checklist` + `module-rule` 基础（标准/清单/版本/合规项/规则/映射） | 清单发布 + 规则发布测试通过 |
| M4 | `module-scan` + `module-engine-adapter`（Semgrep）+ `module-result` 垂直打通 | fixture 扫描→Finding→去重→合规判定端到端测试通过 |
| M5 | `module-report` 基础报表 | scan-summary / compliance-summary / trend 接口测试通过 |

每阶段结束：编译通过、测试通过、可 `docker compose up` 启动。

---

## 15. 验收标准（plan.md 附录）

1. 代码编译通过
2. 单元/集成测试通过
3. 接口文档（springdoc）完整
4. Flyway 迁移脚本完整可执行
5. 关键业务路径有日志
6. 异常统一处理
7. 权限控制正确
8. 审计日志完整
9. 无敏感信息泄露
10. 报表数据与扫描结果一致

---

## 16. 开放问题与决策记录

| 编号 | 问题 | 决策 |
|---|---|---|
| D1 | 模块间跨引用 | 一律 ID / 接口关联，禁止 import 其他模块实体，依赖图无环 |
| D2 | 判定策略 | 结构化配置 + SpEL，不开放任意脚本 |
| D3 | 原始结果存储 | 首发 PostgreSQL JSONB，MinIO/ClickHouse 后续 |
| D4 | 异步机制 | 首发 Spring `@Async` + 任务表，MQ 后续 |
| D5 | 引擎执行方式 | 首发本地命令 + fixture 测试，容器化执行器后续 |
| D6 | 首发引擎 | Semgrep（与 plan.md 推荐一致） |
