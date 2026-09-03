# 代码合规扫描平台 — 第二阶段设计文档（M6–M9）

- 日期：2026-09-03
- 状态：草稿（待确认）
- 依据：`2026-09-02-code-compliance-platform-design.md`（下文称「基线 spec」，M0–M5 已交付）、`plan.md`、`AGENTS.md`、`CLAUDE.md`
- 环境：Java 21、Gradle 8.8 wrapper、PostgreSQL 16（Testcontainers）

## 1. 目标与范围

本设计文档定义第二阶段增量：在已交付的 M0–M5 垂直链路上补齐**整改闭环、版本化追溯、真实引擎路径、工程化补全**四大能力。

范围由用户决策合并（三项增量全做），里程碑划分为 **M6–M9 四个增量**：

| 里程碑 | 内容 | 对应基线 spec 章节 | 修复的最终评审待办 |
|---|---|---|---|
| M6 | 版本追溯 + Finding 生命周期数据层 | §4.4–4.6、§5.1、§8 | #5（checklist_version_id）、#1（复扫归属）的数据基础 |
| M7 | 整改闭环（remediation） | §4.5、§8「整改后重新扫描」 | #1 业务闭环 |
| M8 | 真实引擎路径 | §7 Adapter 契约、§8 主流程 | #4（真实 SEMGREP 路径） |
| M9 | 工程化补全 | §6 API、§11 RBAC、§13 错误处理 | 8 个 Minors |

基线 spec 的**全局约束、模块划分、分层规范、枚举规范、安全红线**继续约束本阶段，除非本文件明确变更（见第 2 节决策记录）。

### 1.1 非目标（延续基线 spec §1.3，仍延后）

Redis/MQ、MinIO/ES/ClickHouse、多引擎真实集成（仍仅 Semgrep 一个 Adapter）、企微/钉钉/飞书通知渠道、定时扫描/Webhook、多租户组织层级、AI 修复建议。本阶段 `module-notification` 只落地**接口抽象 + 站内信/日志占位**。

---

## 2. 决策记录（本阶段新增/变更）

| 编号 | 问题 | 决策 |
|---|---|---|
| P2-D1 | 里程碑划分 | M6–M9 四个增量（用户已确认）。M6 为 M7 打数据地基；M8/M9 相互独立，置于其后。 |
| P2-D2 | 复扫命中已有 finding 的归属 | **规范行 + 历史追加**（用户已确认）：每 `(projectId, fingerprint)` 一条 finding 规范行；每次扫描命中追加 `finding_history`；`scan_task_id` 语义 = 首次发现任务。扫描任务视图经 history join（occurrence 查询）取该次扫描全部命中。否决「每扫描独立实例」（违反基线 §7.3 跨任务状态机复用）。 |
| P2-D3 | FindingStatus 枚举 | 基线 §4.8 的 11 态替换现有 5 态；存量数据映射见 M6 迁移。 |
| P2-D4 | 生命周期状态机归属 | **唯一权威是 `finding.status`**（module-result 拥有）。`module-remediation` 存工作流元数据（受让人/计划/期限/证据引用），其 `remediation_task.status` 仅作同事务内的冗余缓存列，供查询过滤，与 `finding.status` 同事务写入，禁止第二权威。 |
| P2-D5 | 依赖规则例外 | 基线 Global Constraint #1 增加**第二个例外**（类比 `module-scan`）：`module-remediation` 可依赖 `module-result` 的**接口与值类型**（`FindingLifecyclePort`、`FindingStatus` 枚举、`FindingView` DTO），禁止 import `@Entity`。 |
| P2-D6 | 引擎 checkout 归属 | **编排器层**（module-scan 的 `GitCheckout` 组件）负责检出，引擎无关；adapter 只消费 `ScanContext.workDir`。符合基线 §3.1 依赖约束（module-engine-adapter 不依赖 scan）。 |
| P2-D7 | OpenAPI Token 管理 | **token 表多 CI 管理**（用户已确认，否决单 env token）：`openapi_token` 表按 CI 标识建 token，可独立启用/禁用/过期，token 只存哈希（红线上限）。CI 触发端点经 `X-API-Token` 过滤器鉴权；管理端点走 JWT+ADMIN。 |
| P2-D8 | Adapter 契约演进 | 基线 §7.1 五方法契约落地，**带默认实现**：现有 STUB/测试适配器无需实现全部方法即可工作；真实路径按需覆写。 |

---

## 3. M6 — 版本追溯 + Finding 生命周期数据层

目标：让「整改后重新扫描产生新任务」具备正确归属与版本可追溯；为 M7 状态机落地数据基础。

### 3.1 数据库变更（V8 迁移）

```sql
-- (1) finding：补 project_id（回填自 scan_task，随后 NOT NULL）
ALTER TABLE finding ADD COLUMN project_id BIGINT;
UPDATE finding f SET project_id = (SELECT t.project_id FROM scan_task t WHERE t.id = f.scan_task_id)
    WHERE f.project_id IS NULL;
ALTER TABLE finding ALTER COLUMN project_id SET NOT NULL;
CREATE INDEX idx_finding_project ON finding(project_id);

-- (2) 状态快照：每次状态转移写一行（finding.status 镜像最新）
CREATE TABLE finding_status (
    id         BIGSERIAL PRIMARY KEY,
    finding_id BIGINT      NOT NULL,
    status     VARCHAR(16) NOT NULL,
    changed_by BIGINT,
    changed_at TIMESTAMP   NOT NULL DEFAULT now(),
    reason     TEXT,
    created_at TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_finding_status_finding ON finding_status (finding_id, changed_at DESC);

-- (3) 扫描出现历史（只增不改）：每次扫描命中该 fingerprint 写一行。
--     职责边界：finding_history 只记「扫描出现」（CREATED/REAPPEARED）；状态转移走 finding_status + audit_log，不写本表。
CREATE TABLE finding_history (
    id           BIGSERIAL PRIMARY KEY,
    finding_id   BIGINT      NOT NULL,
    scan_task_id BIGINT      NOT NULL,
    action       VARCHAR(16) NOT NULL,   -- CREATED（首次发现）/ REAPPEARED（复扫复现）
    changed_by   BIGINT,
    changed_at   TIMESTAMP   NOT NULL DEFAULT now(),
    detail       TEXT,
    created_at   TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_finding_history_finding ON finding_history (finding_id, changed_at DESC);
CREATE INDEX idx_finding_history_scan  ON finding_history (scan_task_id, finding_id);

-- (4) 证据
CREATE TABLE finding_evidence (
    id            BIGSERIAL PRIMARY KEY,
    finding_id    BIGINT       NOT NULL,
    evidence_type VARCHAR(32)  NOT NULL,  -- FIX_COMMIT / SCREENSHOT / DOC / LINK
    evidence_ref  VARCHAR(512) NOT NULL,
    added_by      BIGINT,
    added_at      TIMESTAMP    NOT NULL DEFAULT now(),
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_finding_evidence_finding ON finding_evidence (finding_id);

-- (5) scan_task：版本追溯 + 运行元数据（基线 §4.4）
ALTER TABLE scan_task ADD COLUMN checklist_version_id BIGINT;
ALTER TABLE scan_task ADD COLUMN rule_ids           JSONB;
ALTER TABLE scan_task ADD COLUMN commit_id          VARCHAR(64);
ALTER TABLE scan_task ADD COLUMN duration_ms        BIGINT;
ALTER TABLE scan_task ADD COLUMN request_id         VARCHAR(64);

-- (6) 评估结果表补版本（基线 §4.6）
ALTER TABLE checklist_item_result ADD COLUMN checklist_version_id BIGINT;
ALTER TABLE compliance_evaluation ADD COLUMN checklist_version_id BIGINT;

-- (7) checklist 唯一约束（Minors 清理）
ALTER TABLE checklist_version ADD CONSTRAINT uq_checklist_version_no  UNIQUE (checklist_id, version_no);
ALTER TABLE checklist_item    ADD CONSTRAINT uq_item_code_version      UNIQUE (checklist_version_id, item_code);
-- 约束名以任务实施时核对现有 DDL 为准，若已存在则跳过
```

### 3.2 FindingStatus 11 态与存量映射

基线 §4.8 枚举（逐字）：

```text
FindingStatus: NEW / CONFIRMED / ASSIGNED / FIXING / FIXED / RECHECKING /
               CLOSED / IGNORED / FALSE_POSITIVE / ACCEPTED_RISK / WAIVED
```

存量映射（V8 内 `UPDATE ... SET status = CASE ...`）：`OPEN→NEW`、`REOPENED→CONFIRMED`、`SUPPRESSED→FALSE_POSITIVE`、`FIXED→FIXED`、`WAIVED→WAIVED`。

### 3.3 复扫归属（P2-D2 落地）

- `Finding` 实体新增 `projectId`；`scanTaskId` 保持「首次发现任务」。
- `FindingService.upsertByFingerprint(projectId, scanTaskId, engine, findings)` 改写为：
  - 按 `(projectId, fingerprint)` 查规范行；未命中 → 新建 + `finding_history(CREATED)`；命中 → `occurrenceCount+1`、`lastSeenAt=now`、`finding_history(REAPPEARED)`，**status 由状态机决定**：
    - 当前状态 ∈ 活动集（`NEW/CONFIRMED/ASSIGNED/FIXING/RECHECKING`）→ status 不变（保持在状态机当前位置）；
    - 当前状态 ∈ 终态/修复集（`FIXED/CLOSED`）→ 视为回归：转移回 `CONFIRMED`，写 `finding_status` + `finding_history` + audit，reason=`reappeared_after_fix`；
    - 当前状态 ∈ 豁免集（`WAIVED/IGNORED/FALSE_POSITIVE/ACCEPTED_RISK`）→ 按基线 §7.3「已豁免跳过」保持终态，仅写 `finding_history(REAPPEARED)`（M7 接线通知）。
- **occurrence 查询**：`FindingRepository.findByProjectScanTask(scanTaskId)` —— 以 `finding_history.scan_task_id = :scanTaskId` 取 finding id，返回这些规范行（含该次扫描新建 + 复现）。`ScanOrchestrator` 评估输入与 `GET /scan-tasks/{id}/findings` 改用此查询；`findByScanTaskId` 原语义保留（frozen 测试兼容，见 §7）。

### 3.4 版本盖章（P2 修复 #5）

- `ChecklistQueryService` 新增 `publishedVersionForProject(projectId): ChecklistVersion?`（绑定表取最新 binding → 版本须 PUBLISHED，返回 null 表示未绑定/未发布）。
- 编排器扫描开始：解析 `publishedVersionForProject` → 盖章到 `ScanTask.checklistVersionId` 与 `rule_ids`（本次生效规则集）→ `ComplianceEvaluator` 接收 versionId，`ChecklistItemResult`/`ComplianceEvaluation` 全部落 `checklist_version_id`。
- `ScanTask` 运行元数据补齐：`commit_id`（M8 检出后回填；STUB 置 null）、`duration_ms`、`request_id`。

---

## 4. M7 — 整改闭环（module-remediation）

### 4.1 实体（V9 迁移）

```sql
CREATE TABLE remediation_task (
    id               BIGSERIAL PRIMARY KEY,
    finding_id       BIGINT      NOT NULL,
    project_id       BIGINT      NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'OPEN',  -- 冗余缓存列，权威=finding.status
    assignee_user_id BIGINT,
    plan             TEXT,
    due_date         DATE,
    created_by       BIGINT,
    created_at       TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_remediation_finding  ON remediation_task (finding_id);
CREATE INDEX idx_remediation_project  ON remediation_task (project_id, status);
```

### 4.2 状态机与转移矩阵

权威状态机在 `finding.status`（module-result 的 `FindingLifecyclePort`），module-remediation 编排。转移矩阵（粗粒度，与 M9 RBAC 一致）：

| 转移 | 允许角色 | 说明 |
|---|---|---|
| `NEW → CONFIRMED` | COMPLIANCE_MANAGER、PROJECT_OWNER | 人工确认问题真实存在 |
| `CONFIRMED → ASSIGNED` | COMPLIANCE_MANAGER、PROJECT_OWNER | 分配受让人（写 remediation_task.assignee_user_id） |
| `ASSIGNED → FIXING` | 受让人(DEVELOPER)、PROJECT_OWNER | 开始整改 |
| `FIXING → FIXED` | 受让人(DEVELOPER) | 必附 evidence（FIX_COMMIT 等）与 plan |
| `FIXED → RECHECKING` | COMPLIANCE_MANAGER、PROJECT_OWNER | 触发复扫（新 ScanTask） |
| `RECHECKING → CLOSED` / 回归 `CONFIRMED` | 系统（复扫结果） | 复扫缺席→CLOSED（整改验证通过）；复现→回 CONFIRMED（回归，audit 留痕） |
| 任意 → `IGNORED/FALSE_POSITIVE/ACCEPTED_RISK/WAIVED` | COMPLIANCE_MANAGER | 终态，必附 evidence + reason |
| `CLOSED` 复现 → `CONFIRMED` | 系统 | 与 3.3 规则一致 |

### 4.3 复扫验证闭环

`FIXED → RECHECKING` 时创建复扫 ScanTask（`trigger_type=MANUAL`，reason 记入 finding_status）；复扫完成、orchestrator 落库后，`RemediationVerifier` 对处于 RECHECKING 的 finding 执行：
- 该次扫描 occurrence 查询**未命中** → `CLOSED`（verification_passed）；
- **命中** → 回归 `CONFIRMED`（regression）。

### 4.4 接口与 API

`module-result` 发布（P2-D5 例外）：
- `interface FindingLifecyclePort`：`transition(findingId, to, reason, changedBy)`、`addEvidence(findingId, type, ref, changedBy)`、`findingsForScanTask(scanTaskId): List<FindingView>`。
- `FindingView` DTO（id、ruleCode、severity、status、filePath、lineNumber、firstSeenAt、lastSeenAt、occurrenceCount）。

`module-remediation` 端点（`/api/v1/remediation/**`，统一响应）：

| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/v1/remediation/findings` | GET | 按项目/状态/严重级分页查询（含 remediation_task 元数据） |
| `/api/v1/remediation/findings/{id}/confirm` | POST | NEW→CONFIRMED |
| `/api/v1/remediation/findings/{id}/assign` | POST | →ASSIGNED，带 assigneeId/plan/dueDate |
| `/api/v1/remediation/findings/{id}/fixing` | POST | →FIXING |
| `/api/v1/remediation/findings/{id}/fixed` | POST | →FIXED，附 evidence |
| `/api/v1/remediation/findings/{id}/recheck` | POST | →RECHECKING 并创建复扫任务 |
| `/api/v1/remediation/findings/{id}/evidence` | POST | 追加证据 |
| `/api/v1/remediation/findings/{id}/status` | PUT | 终态转移（IGNORED/FALSE_POSITIVE/ACCEPTED_RISK/WAIVED），附 reason+evidence |

每次转移：写 `finding_status`（转移日志，追加）+ `AuditService`（module-common）；`finding_history` 仅记扫描出现（CREATED/REAPPEARED），不记转移。

---

## 5. M8 — 真实引擎路径

### 5.1 Adapter 契约（基线 §7.1 落地，P2-D8）

`module-result` 的 `ScanEngineAdapter` 从 `scan(context): ScanResult` 演进为五方法契约（**全部带默认实现**，兼容现有实现）：

```kotlin
interface ScanEngineAdapter {
    val engine: String
    fun supports(engineType: String): Boolean = engineType.equals(engine, ignoreCase = true)
    fun prepareScan(context: ScanContext) {}
    fun executeScan(context: ScanContext): ScanExecutionResult = ScanExecutionResult(success = true)
    fun collectResult(context: ScanContext): List<RawFinding> = emptyList()
    fun normalizeResult(context: ScanContext, raw: List<RawFinding>): List<RawFinding> = raw
    fun cleanup(context: ScanContext) {}
}
```

- `ScanExecutionResult(success, errorMessage, durationMs, stdoutRef?)`。
- `ScanContext` 扩展：

```kotlin
data class ScanContext(
    val scanTaskId: Long,
    val projectId: Long,
    val repoUrl: String,
    val ref: String? = null,
    val workDir: String? = null,        // 编排器检出的本地目录
    val commitId: String? = null,
    val timeoutSeconds: Long? = null,
    val paramsJson: String? = null,     // rule_engine_binding.parameters
    val configJson: String? = null,     // 兼容保留
)
```

- 编排器调用序列：`prepareScan → executeScan → collectResult → normalizeResult → cleanup`（cleanup 放 `finally`）。`ScanExecutionResult` 失败 → 按 `retryPolicy` 重试（`ScanJob.retryCount/maxRetry`），重试耗尽 → 任务失败；多引擎部分失败 → `PARTIAL_SUCCESS`（基线 §13）。

### 5.2 GitCheckout（module-scan，引擎无关）

```kotlin
interface GitCheckout { fun checkout(repoUrl: String, ref: String?): CheckoutResult }
data class CheckoutResult(val workDir: String, val commitId: String?)
```

- 真实路径：`git clone --depth 1 [-b ref] <repoUrl> <tempDir>`，`git rev-parse HEAD` 回填 `commitId`；编排器 `finally` 删除临时目录。
- 本地路径 / 测试（STUB）路径：跳过 clone，`workDir = repoUrl`（或空）。STUB 适配器默认方法不消费 workDir，零改动。
- `SemgrepAdapter`：优先 `context.workDir` 作为扫描目标，缺失时回退 `repoUrl`；`SemgrepCli` 已具备超时与临时文件重定向（本阶段验证，不再改动）。severity 映射保持在 `normalizeResult`（Semgrep→平台）。

### 5.3 生命周期状态

`ScanTask` 状态补全：编排器在创建 ScanJob 前置 `PREPARING`；单引擎失败但任务整体有已成功产出 → `PARTIAL_SUCCESS`；取消路径置 `CANCELLED` 并中断未完成 Job（沿用现有 cancel 端点）。

---

## 6. M9 — 工程化补全

### 6.1 RBAC 粗粒度矩阵（基线 §11）

SecurityConfig 以 URL+Method 规则落地（角色取 `ROLE_` 前缀 authority）：

| 资源 | ADMIN | COMPLIANCE_MANAGER | PROJECT_OWNER | DEVELOPER | AUDITOR |
|---|---|---|---|---|---|
| `/api/v1/admin/**` | ✓ | – | – | – | – |
| `/api/v1/users/**`（写/读） | ✓ | – | – | – | – |
| `/api/v1/compliance/**`、`/api/v1/rules/**` 写 | ✓ | ✓ | – | – | – |
| `/api/v1/compliance/**`、`/api/v1/rules/**` 读 | ✓ | ✓ | ✓ | ✓ | ✓ |
| `/api/v1/projects/**` 写 | ✓ | ✓ | ✓ | – | – |
| `/api/v1/projects/**`、`/api/v1/scan-tasks/**`、`/api/v1/reports/**` 读 | ✓ | ✓ | ✓ | ✓ | ✓ |
| `/api/v1/scan-tasks` POST / cancel | ✓ | ✓ | ✓ | – | – |
| `/api/v1/remediation/**` 写 | ✓ | ✓（confirm/waive/review） | ✓（assign/fix） | ✓（fix 受让人） | – |
| `/api/v1/remediation/**` 读 | ✓ | ✓ | ✓ | ✓ | ✓ |
| `/api/v1/openapi/scans`（CI 触发） | JWT 或 API Token | | | | |

> 说明：DEVELOPER 的 fix 仅限受让人自身，属数据级约束，MVP 以「任意已登录用户可调 fixed 端点、服务端校验受让人」实现（细粒度数据权限仍延后，见基线 §11）。

### 6.2 module-admin

`/api/v1/admin/dashboard`（项目/任务/finding 计数与严重级分布）、`/api/v1/admin/scans`（任务分页+过滤）、`/api/v1/admin/findings`（全局 finding 分页）。ADMIN 专属。

### 6.3 module-openapi（P2-D7：token 表多 CI）

V10 迁移：

```sql
CREATE TABLE openapi_token (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(64)  NOT NULL,           -- CI 标识，如 jenkins-main
    token_hash   VARCHAR(128) NOT NULL,           -- 只存哈希（BCrypt），明文只在创建时返回一次
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / DISABLED
    expires_at   TIMESTAMP,
    last_used_at TIMESTAMP,
    created_by   BIGINT,
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP NOT NULL DEFAULT now()
);
```

- 过滤器：**仅**作用于 `POST /api/v1/openapi/scans`（CI 触发路径）：若请求带 `X-API-Token`，校验哈希 + ACTIVE + 未过期，通过则注入 `ROLE_API` 认证；无 Token 则回落到 JWT 链。**管理端点不受该过滤器影响**，必须 JWT + ADMIN。
- 端点（均在 module-openapi）：`POST /api/v1/openapi/tokens`（ADMIN，创建并返回明文一次）、`GET /api/v1/openapi/tokens`（ADMIN）、`DELETE /api/v1/openapi/tokens/{id}`（ADMIN，可吊销单个）、`POST /api/v1/openapi/scans`（CI 触发：接受 API Token 或 JWT，复用扫描创建服务，`trigger_type=CI`）。

### 6.4 module-notification

`interface NotificationSender { fun send(channel: Channel, subject: String, body: String, recipients: List<Long>) }` + `LogNotificationSender`（占位：写日志/站内信表预留）。M7 豁免/回归事件处 best-effort 调用（失败不影响主流程）。渠道（企微/钉钉）仍延后。

### 6.5 tech debt（Minors 清理）

| 项 | 现状 | 修复 |
|---|---|---|
| HTTP 状态映射 | GlobalExceptionHandler 统一 500 | `BusinessException.code` → 对应 HTTP 状态（404→404、400→400、403→403）；`NoResourceFoundException`→404；body 仍含 `{code,message}` |
| secrets | 配置内可能含明文默认值 | `app.*` 敏感项（DB 口令/JWT secret/AES key/openapi token 相关）改 `${ENV:default}` 注入，默认值仅限非敏感 |
| RuleQueryService O(n) | 内存过滤 | `publishedRuleByEngineRuleId` 改 JPQL 单查询（engine, engineRuleId, status=PUBLISHED） |
| checklist 唯一约束 | 缺失 | 见 V8（§3.1） |
| 生命周期状态 | 无 PREPARING/PARTIAL_SUCCESS | 见 §5.3 |
| findingCount 口径 | 仅新建行 | 改用 occurrence 计数（该次扫描全部命中） |
| 死代码/未用依赖 | orchestrator 未用 projectService 等 | 实施时按编译告警清理 |

---

## 7. 兼容性与测试

- **共享 Testcontainers 约束延续**：所有新集成测试数据全局唯一（新前缀如 `REM-*`、`OAPI-*`、`ADM-*`），`SmokeFirstClassOrderer` 不变。
- **frozen 测试影响（写入 ledger 的 ruling）**：
  1. `FindingRepositoryIntegrationTest`：`finding` 加 NOT NULL `project_id` 后，其直接 save 需补 `projectId` 参数 —— 最小修改授权（findByScanTaskId 语义不变）。
  2. `RbacIntegrationTest` 第 3 断言：tech debt 修复 HTTP 404 映射后，`not-401-403` 可恢复为严格 `isNotFound()`。
  3. 测试 STUB 适配器：M8 接口演进后按新契约更新（测试代码，非冻结）。
- **新测试面**：M6 lifecycle/复扫归属/版本盖章；M7 状态机转移 + 复扫验证闭环（STUB 全链路）；M8 adapter 契约 + GitCheckout（本地 fixture 仓库）+ retry/PARTIAL_SUCCESS；M9 RBAC 矩阵 + admin/openapi（token 建/吊销/鉴权）+ HTTP 状态映射。
- **门禁**：每里程碑结束 `./gradlew build` 全绿；最终 10 条验收标准（基线 §15）逐条复核。

---

## 8. 里程碑验收标准

| 里程碑 | 完成标准 |
|---|---|
| M6 | V8 迁移可执行；finding 带 project_id 与 11 态；复扫 occurrence 归属正确（单元+集成）；版本盖章贯通 scan→result→evaluation |
| M7 | 状态机全转移可测（单元+API）；复扫验证闭环端到端（STUB 全链路，absent→CLOSED / present→回归） |
| M8 | 五方法契约落地，STUB 兼容；GitCheckout 检出本地 fixture 并回填 commitId；cleanup 不泄漏临时目录；retry/PARTIAL_SUCCESS 可测 |
| M9 | RBAC 矩阵集成测试通过；admin/openapi（token 表 CRUD+鉴权）/notification 落地；HTTP 状态映射与 secrets 注入验证；全量 8+Minors 关闭 |

每阶段结束：编译通过、测试通过、`docker compose up` 可启动。
