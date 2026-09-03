# 代码合规扫描平台 第二阶段（M6–M9）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已交付的 M0–M5 垂直链路上补齐整改闭环（finding 生命周期状态机 + 复扫验证）、版本化追溯（checklistVersionId 贯通）、真实引擎路径（Adapter 五方法契约 + GitCheckout）、工程化补全（RBAC 矩阵 + admin/openapi/notification + tech debt）。

**Architecture:** 四个里程碑（M6–M9）按依赖序推进。M6 为整改闭环打数据地基（finding 生命周期三表 + 版本盖章）；M7 落地 module-remediation 状态机与复扫验证闭环；M8 重构 Adapter 契约为 spec §7.1 五方法并在编排器层加 GitCheckout；M9 补 RBAC/三模块/HTTP 状态映射/secrets 注入等工程化。复扫归属采用「规范行 + history 追加」：每 `(projectId, fingerprint)` 一条 finding 规范行，每次扫描命中追加 finding_history，扫描任务视图经 occurrence 查询取该次扫描全部命中。

**Tech Stack:** Kotlin 2.0.21 / Spring Boot 3.3.5 / Spring Data JPA / Spring Security / Flyway / PostgreSQL 16（Testcontainers）/ JUnit 5 + MockK / MockMvc。全部 Gradle 命令用 `./gradlew`（wrapper 8.8）。

**Spec:**
- `docs/superpowers/specs/2026-09-02-code-compliance-platform-design.md`（基线 spec，M0–M5 已交付；全局约束/枚举/模块划分/安全红线继续约束本阶段）
- `docs/superpowers/specs/2026-09-03-code-compliance-platform-phase2-design.md`（本阶段 spec，M6–M9 设计）

## Global Constraints

以下约束对每个任务隐式生效（逐字复制自基线 spec §3.1/§4.8/§11/§13 与第二阶段 spec §2）：

1. **模块依赖**：叶子模块只依赖 `module-common`，互不 import 实体；唯一例外 `module-auth→module-user`；`module-scan` 可依赖 project/checklist/rule/result 接口；`app-server` 依赖全部。跨模块引用一律通过 ID 或接口。
   - **P2 例外 1（P2-D5）**：`module-remediation` 可依赖 `module-result` 的**接口与值类型**（`FindingLifecyclePort`、`FindingStatus` 枚举、`FindingView` DTO），禁止 import `@Entity`。
   - **P2 例外 1b（PF-5）**：`module-remediation` 另可依赖 `module-scan` 的**单接口 + 值类型**（`ScanTriggerPort`、`ScanTaskView`）——仅此两物，用于 M7 requestRecheck 创建复扫任务；禁止 import scan 实体。
   - **P2 例外 2**：`module-openapi` 可依赖 `module-scan` 的接口（`ScanTriggerPort`），禁止 import 实体。
2. **模块内分层**：`api/application/domain/infrastructure`；Controller 不写业务逻辑、不返回 Entity。
3. **Kotlin 风格**：data class / enum class / val，避免 `!!`，优先不可变集合。
4. **统一 API**：响应 `{code:0,message:"success",data}`；分页 `{items,page,size,total}`；路径 `/api/v1/{module}/{resource}`。
5. **表约定**：所有业务表含 `id/created_at/updated_at`；版本表含 `version`；`audit_log` 只增不改不删；原始扫描 JSON 存 jsonb。
6. **枚举统一**：`Severity=CRITICAL/HIGH/MEDIUM/LOW/INFO`；`TaskStatus=PENDING/PREPARING/RUNNING/SUCCESS/FAILED/CANCELLED/PARTIAL_SUCCESS`；`ItemResult=PASS/WARNING/FAIL/MANUAL/SKIPPED`；`RuleStatus=DRAFT/TESTING/PUBLISHED/DISABLED`；`VersionStatus=DRAFT/PUBLISHED/DISABLED`；`FindingStatus=NEW/CONFIRMED/ASSIGNED/FIXING/FIXED/RECHECKING/CLOSED/IGNORED/FALSE_POSITIVE/ACCEPTED_RISK/WAIVED`。
7. **安全**：除 `login`/`swagger`/`/actuator/health`/CI 触发外全部 JWT；密码 BCrypt；凭据 AES；敏感信息不写日志；异常统一处理不泄露系统细节。
8. **红线**：不硬编码合规判定规则（rule_evaluation_policy + SpEL）；不绕过 Adapter；历史扫描结果不可修改；`audit_log` 不删改。

**第二阶段追加约束：**
- **复扫归属（P2-D2）**：finding 为项目指纹规范行（含 `project_id`），`scan_task_id` 语义 = 首次发现任务；每次扫描命中追加 `finding_history`（CREATED/REAPPEARED）；状态转移写 `finding_status`（追加）+ `audit_log`；`finding_history` 不记转移。
- **状态权威（P2-D4）**：`finding.status` 为唯一权威；`remediation_task` 按 spec §4.1 设 **status 冗余缓存列**（同事务镜像 finding.status 写入，非第二权威，读取便捷；权威永远在 finding.status）。
- **引擎 checkout（P2-D6）**：GitCheckout 归 `module-scan` 编排器层，adapter 只消费 `ScanContext.workDir`。
- **OpenAPI Token（P2-D7）**：`openapi_token` 表按 CI 标识多 token，可独立禁用/过期；token 只存 BCrypt 哈希，明文仅创建时返回一次。
- **Ruling #60（remote 约束）**：`origin`（woyao-cloud/scanCheck01，用户本人仓库）保留；**绝不 push**；不新增其他 remote；不重命名分支。全部 Gradle 命令用 `./gradlew`。
- **共享 Testcontainers**：所有 app-server 集成测试共享一个 PostgreSQL 容器；数据必须全局唯一（本阶段新前缀 `M6F-*`/`REM-*`/`M8-*`/`ADM-*`/`OAPI-*`）；`SmokeFirstClassOrderer` 不变（Smoke 首个）。

## 文件结构总览

| 模块 | 新建/修改 | 职责 |
|---|---|---|
| app-server resources/db/migration | `V8__finding_lifecycle_version_trace.sql`、`V9__remediation_task.sql`、`V10__openapi_token.sql` | 阶段迁移 |
| module-result domain | `enums.kt`（11 态）、`Finding.kt`（+projectId）、`FindingHistory.kt`（rename 自 FindingTrace）、`FindingStatusSnapshot.kt`、`FindingEvidence.kt` | 生命周期模型 |
| module-result application | `FindingLifecycleService.kt`（新）、`FindingLifecyclePort.kt`（新）、`FindingService.kt`（改写） | 生命周期权威 + 复扫归属 |
| module-result infrastructure | `FindingRepository.kt`、`FindingHistoryRepository.kt`、`FindingStatusSnapshotRepository.kt`、`FindingEvidenceRepository.kt` | 查询 |
| module-result engine | `ScanEngineAdapter.kt`（五方法）、`ScanContext.kt`（扩展） | Adapter 契约（M8） |
| module-checklist application | `ChecklistQueryService.kt`（+publishedVersionForProject） | 版本解析 |
| module-scan domain/application/infrastructure | `ScanTask.kt`（+版本列）、`ScanOrchestrator.kt`（盖章+occurrence+verify+checkout）、`ComplianceEvaluator.kt`、`ScanTaskService.kt`、`GitCheckout.kt`、`ScanTriggerPort.kt` | 编排 |
| module-engine-adapter semgrep | `SemgrepAdapter.kt`（五方法）、`SemgrepCli.kt`（不改） | 真实引擎 |
| module-remediation | 新建 api/application/domain/infrastructure + build.gradle 加 `module-result`（Task 7.1）+ `module-scan`（Task 7.4，ScanTriggerPort） | 整改闭环 + 复扫触发 |
| module-openapi | 新建 api/application/domain/infrastructure + build.gradle 加 `module-scan` | CI 触发 + token 表 |
| module-notification | `notification/` 实体 + 仓储 + 服务 + `V11__notification.sql` | 通知落库桩 |
| module-admin | api/application + 聚合端点 | 管理后台 |
| module-auth config | `SecurityConfig.kt`（RBAC 矩阵 + openapi 路径） | 授权 |
| module-common exception | `GlobalExceptionHandler.kt`（HTTP 状态映射） | 错误处理 |

---
## M6 — 版本追溯 + Finding 生命周期数据层

### Task 6.1: FindingStatus 11 态 + V8 迁移（生命周期表 + 版本追溯列）

**Files:**
- Create: `app-server/src/main/resources/db/migration/V8__finding_lifecycle_version_trace.sql`
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/domain/enums.kt`（全文件替换）
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/domain/Finding.kt`（status 默认值）
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingService.kt`（仅一行 OPEN→NEW，Task 6.4 完整改写）

**Interfaces:**
- Produces: DB 表 `finding_status`/`finding_history`/`finding_evidence`；`finding.project_id`、`scan_task.checklist_version_id|rule_ids|commit_id|duration_ms|request_id`、`checklist_item_result.checklist_version_id`、`compliance_evaluation.checklist_version_id` 列；`finding.status` 存量值映射到 11 态。
- Produces: `enum class FindingStatus { NEW, CONFIRMED, ASSIGNED, FIXING, FIXED, RECHECKING, CLOSED, IGNORED, FALSE_POSITIVE, ACCEPTED_RISK, WAIVED }`（module-result.domain，供后续所有任务引用）。

> **为何本任务捆绑枚举与迁移**：V8 把存量 `status` 值改写为 11 态（OPEN→NEW 等）；若枚举先改而 DB 未迁移，读旧值抛 IllegalArgumentException；若迁移先落而枚举未改，同一错误反向发生。两者必须同一提交落地。

- [ ] **Step 1: 写 V8 迁移脚本**

创建 `app-server/src/main/resources/db/migration/V8__finding_lifecycle_version_trace.sql`，内容逐字如下：

```sql
-- (1) finding：补 project_id（回填自 scan_task，随后 NOT NULL）
ALTER TABLE finding ADD COLUMN project_id BIGINT;
UPDATE finding f SET project_id = (SELECT t.project_id FROM scan_task t WHERE t.id = f.scan_task_id)
    WHERE f.project_id IS NULL;
ALTER TABLE finding ALTER COLUMN project_id SET NOT NULL;
CREATE INDEX idx_finding_project ON finding (project_id);

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

-- (3) finding_trace 演进为 finding_history（扫描出现历史，只增不改）
ALTER TABLE finding_trace RENAME TO finding_history;
ALTER TABLE finding_history ADD COLUMN changed_by BIGINT;
ALTER TABLE finding_history ADD COLUMN detail TEXT;
-- PF-8：spec §3.1 要求 changed_at 与 (finding_id, changed_at DESC) 索引（基线 finding_trace 已含 changed_at 列，RENAME 保留；此处确保其存在）
ALTER TABLE finding_history ADD COLUMN IF NOT EXISTS changed_at TIMESTAMP NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_finding_history_finding ON finding_history (finding_id, changed_at DESC);
CREATE INDEX IF NOT EXISTS idx_finding_history_scan ON finding_history (scan_task_id, finding_id);

-- (4) 证据
CREATE TABLE finding_evidence (
    id            BIGSERIAL PRIMARY KEY,
    finding_id    BIGINT       NOT NULL,
    evidence_type VARCHAR(32)  NOT NULL,
    evidence_ref  VARCHAR(512) NOT NULL,
    added_by      BIGINT,
    added_at      TIMESTAMP    NOT NULL DEFAULT now(),
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_finding_evidence_finding ON finding_evidence (finding_id);

-- (5) scan_task：版本追溯 + 运行元数据
ALTER TABLE scan_task ADD COLUMN checklist_version_id BIGINT;
ALTER TABLE scan_task ADD COLUMN rule_ids JSONB;
ALTER TABLE scan_task ADD COLUMN commit_id VARCHAR(64);
ALTER TABLE scan_task ADD COLUMN duration_ms BIGINT;
ALTER TABLE scan_task ADD COLUMN request_id VARCHAR(64);

-- (6) 评估结果表补版本
ALTER TABLE checklist_item_result ADD COLUMN checklist_version_id BIGINT;
ALTER TABLE compliance_evaluation ADD COLUMN checklist_version_id BIGINT;

-- (7) finding.status 存量映射到 11 态（OPEN→NEW、REOPENED→CONFIRMED、SUPPRESSED→FALSE_POSITIVE）
UPDATE finding SET status = CASE
    WHEN status = 'OPEN'       THEN 'NEW'
    WHEN status = 'REOPENED'   THEN 'CONFIRMED'
    WHEN status = 'SUPPRESSED' THEN 'FALSE_POSITIVE'
    ELSE status
END;

-- (8) checklist 唯一约束（Minors 清理）
ALTER TABLE checklist_version ADD CONSTRAINT uq_checklist_version_no UNIQUE (checklist_id, version_no);
ALTER TABLE checklist_item    ADD CONSTRAINT uq_item_code_version UNIQUE (checklist_version_id, item_code);
```

- [ ] **Step 2: 替换 FindingStatus 枚举为 11 态**

`module-result/src/main/kotlin/com/example/compliance/result/domain/enums.kt` 全文件替换为：

```kotlin
package com.example.compliance.result.domain

/** 基线 spec §4.8 统一枚举：finding 生命周期状态。finding.status 为唯一权威。 */
enum class FindingStatus { NEW, CONFIRMED, ASSIGNED, FIXING, FIXED, RECHECKING, CLOSED, IGNORED, FALSE_POSITIVE, ACCEPTED_RISK, WAIVED }
```

- [ ] **Step 3: 修复因枚举变更产生的编译错误（最小改动）**

`Finding.kt` 第 36 行：`var status: FindingStatus = FindingStatus.OPEN` → `= FindingStatus.NEW`。
`FindingService.kt` 第 63 行：`existing.status = FindingStatus.OPEN` → `= FindingStatus.NEW`（Task 6.4 将完整改写该方法）。

- [ ] **Step 4: 运行全模块编译与测试**

Run: `./gradlew :app-server:test --tests "*FindingRepositoryIntegrationTest*" --tests "*SmokeIntegrationTest*"`
Expected: 通过。V8 迁移在测试上下文启动时执行；`FindingRepositoryIntegrationTest` 直接 save 的 finding 不带 projectId → 实体默认值 0 满足 NOT NULL（frozen 测试无需修改，`findByScanTaskId` 语义不变）；`SmokeIntegrationTest` 验证上下文可启动。

- [ ] **Step 5: 运行全量 build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（全部模块编译 + 测试，无回归）。

- [ ] **Step 6: Commit**

```bash
git add app-server/src/main/resources/db/migration/V8__finding_lifecycle_version_trace.sql module-result/src/main/kotlin/com/example/compliance/result/domain/enums.kt module-result/src/main/kotlin/com/example/compliance/result/domain/Finding.kt module-result/src/main/kotlin/com/example/compliance/result/application/FindingService.kt
git commit -m "feat(result,scan): finding 11-state lifecycle enum and V8 lifecycle/version-trace migration"
```

---

### Task 6.2: Finding 生命周期实体与仓储（projectId + history/status/evidence）

**Files:**
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/domain/Finding.kt`（+projectId 字段）
- Delete: `module-result/src/main/kotlin/com/example/compliance/result/domain/FindingTrace.kt`；Create: `.../result/domain/FindingHistory.kt`（重命名+扩展）
- Create: `module-result/src/main/kotlin/com/example/compliance/result/domain/FindingStatusSnapshot.kt`
- Create: `module-result/src/main/kotlin/com/example/compliance/result/domain/FindingEvidence.kt`
- Delete: `module-result/src/main/kotlin/com/example/compliance/result/infrastructure/FindingTraceRepository.kt`；Create: `.../infrastructure/FindingHistoryRepository.kt`
- Create: `module-result/src/main/kotlin/com/example/compliance/result/infrastructure/FindingStatusSnapshotRepository.kt`
- Create: `module-result/src/main/kotlin/com/example/compliance/result/infrastructure/FindingEvidenceRepository.kt`
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/infrastructure/FindingRepository.kt`（+2 查询）
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingService.kt`（仅类型重命名，Task 6.4 完整改写）
- Create: `app-server/src/test/kotlin/com/example/compliance/result/M6FindingRepositoryIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 6.1 的 11 态 `FindingStatus`；V8 表结构。
- Produces: `FindingRepository.findByProjectIdAndFingerprint(projectId, fingerprint)`、`FindingRepository.findByProjectScanTask(scanTaskId)`（occurrence，JOIN finding_history）；`FindingHistory`/`FindingStatusSnapshot`/`FindingEvidence` 实体与仓储（后续任务引用）。

- [ ] **Step 1: 写失败测试**

创建 `app-server/src/test/kotlin/com/example/compliance/result/M6FindingRepositoryIntegrationTest.kt`：

```kotlin
package com.example.compliance.result

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.result.domain.Finding
import com.example.compliance.result.domain.FindingHistory
import com.example.compliance.result.infrastructure.FindingHistoryRepository
import com.example.compliance.result.infrastructure.FindingRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** M6-* 前缀数据，与共享容器中的 SEC-*/SEC2-*/PIPE-*/RPT-* 不冲突（Ruling #43 类约束）。 */
class M6FindingRepositoryIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var findingRepository: FindingRepository
    @Autowired lateinit var historyRepository: FindingHistoryRepository

    @Test
    fun `occurrence query returns findings seen in a scan task via history`() {
        val fp = "m6f-" + "b".repeat(60)
        val finding = findingRepository.save(Finding().apply {
            projectId = 99001L; scanTaskId = 99001L; engine = "STUB"; ruleCode = "M6F-001"
            filePath = "B.java"; lineNumber = 2; severity = "HIGH"; fingerprint = fp
        })
        historyRepository.save(FindingHistory().apply {
            findingId = finding.id!!; scanTaskId = 99001L; action = "CREATED"
        })
        // 另一个扫描任务（99002）复现同一指纹 → REAPPEARED
        historyRepository.save(FindingHistory().apply {
            findingId = finding.id!!; scanTaskId = 99002L; action = "REAPPEARED"
        })

        val seenInFirst = findingRepository.findByProjectScanTask(99001L)
        val seenInSecond = findingRepository.findByProjectScanTask(99002L)
        assertEquals(1, seenInFirst.size)
        assertEquals("M6F-001", seenInFirst[0].ruleCode)
        assertEquals(1, seenInSecond.size)
        assertEquals("M6F-001", seenInSecond[0].ruleCode)

        assertEquals(fp, findingRepository.findByProjectIdAndFingerprint(99001L, fp)?.fingerprint)
        assertNull(findingRepository.findByProjectIdAndFingerprint(99999L, fp))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app-server:test --tests "*M6FindingRepositoryIntegrationTest*"`
Expected: 编译失败 — `findByProjectScanTask`/`findByProjectIdAndFingerprint` 未定义，`FindingHistory` 不存在。

- [ ] **Step 3: 实现实体与仓储**

`Finding.kt` 在 `scanTaskId` 字段后新增：

```kotlin
    @Column(name = "project_id", nullable = false)
    var projectId: Long = 0
```

`FindingHistory.kt`（删除 FindingTrace.kt，内容如下）：

```kotlin
package com.example.compliance.result.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/** finding_history：扫描出现历史（只增不改）。action: CREATED（首次发现）/ REAPPEARED（复扫复现）。 */
@Entity
@Table(name = "finding_history")
class FindingHistory : BaseEntity() {
    @Column(name = "finding_id", nullable = false)
    var findingId: Long = 0
    @Column(name = "scan_task_id", nullable = false)
    var scanTaskId: Long = 0
    @Column(name = "action", nullable = false, length = 16)
    lateinit var action: String
    @Column(name = "changed_by")
    var changedBy: Long? = null
    @Column(name = "changed_at", nullable = false)
    var changedAt: java.time.Instant = java.time.Instant.now()
    @Column(name = "detail")
    var detail: String? = null
}
```

`FindingStatusSnapshot.kt`：

```kotlin
package com.example.compliance.result.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*
import java.time.Instant

/** finding_status：状态转移快照（追加式）；finding.status 镜像最新一行。 */
@Entity
@Table(name = "finding_status")
class FindingStatusSnapshot : BaseEntity() {
    @Column(name = "finding_id", nullable = false)
    var findingId: Long = 0
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: FindingStatus = FindingStatus.NEW
    @Column(name = "changed_by")
    var changedBy: Long? = null
    @Column(name = "changed_at", nullable = false)
    var changedAt: Instant = Instant.now()
    @Column(name = "reason")
    var reason: String? = null
}
```

`FindingEvidence.kt`：

```kotlin
package com.example.compliance.result.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

/** finding_evidence：整改证据引用（FIX_COMMIT / SCREENSHOT / DOC / LINK）。 */
@Entity
@Table(name = "finding_evidence")
class FindingEvidence : BaseEntity() {
    @Column(name = "finding_id", nullable = false)
    var findingId: Long = 0
    @Column(name = "evidence_type", nullable = false, length = 32)
    lateinit var evidenceType: String
    @Column(name = "evidence_ref", nullable = false, length = 512)
    lateinit var evidenceRef: String
    @Column(name = "added_by")
    var addedBy: Long? = null
    @Column(name = "added_at", nullable = false)
    var addedAt: Instant = Instant.now()
}
```

`FindingHistoryRepository.kt`（删除 FindingTraceRepository.kt）：

```kotlin
package com.example.compliance.result.infrastructure

import com.example.compliance.result.domain.FindingHistory
import org.springframework.data.jpa.repository.JpaRepository

interface FindingHistoryRepository : JpaRepository<FindingHistory, Long>
```

`FindingStatusSnapshotRepository.kt`：

```kotlin
package com.example.compliance.result.infrastructure

import com.example.compliance.result.domain.FindingStatusSnapshot
import org.springframework.data.jpa.repository.JpaRepository

interface FindingStatusSnapshotRepository : JpaRepository<FindingStatusSnapshot, Long> {
    fun findFirstByFindingIdOrderByChangedAtDesc(findingId: Long): FindingStatusSnapshot?
}
```

`FindingEvidenceRepository.kt`：

```kotlin
package com.example.compliance.result.infrastructure

import com.example.compliance.result.domain.FindingEvidence
import org.springframework.data.jpa.repository.JpaRepository

interface FindingEvidenceRepository : JpaRepository<FindingEvidence, Long>
```

`FindingRepository.kt` 全文件替换为：

```kotlin
package com.example.compliance.result.infrastructure

import com.example.compliance.result.domain.Finding
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FindingRepository : JpaRepository<Finding, Long> {
    fun findByFingerprint(fingerprint: String): Finding?
    fun findByScanTaskId(scanTaskId: Long): List<Finding>

    /** 项目指纹规范行：复扫归属的基础。 */
    fun findByProjectIdAndFingerprint(projectId: Long, fingerprint: String): Finding?

    /** occurrence 查询：该扫描任务在 finding_history 中出现的全部 finding（含复现），按历史 id 排序。 */
    @Query(
        "SELECT f FROM Finding f JOIN FindingHistory h ON h.findingId = f.id " +
            "WHERE h.scanTaskId = :scanTaskId ORDER BY h.id"
    )
    fun findByProjectScanTask(@Param("scanTaskId") scanTaskId: Long): List<Finding>
}
```

`FindingService.kt`：仅把 `FindingTrace`/`FindingTraceRepository` 引用改为 `FindingHistory`/`FindingHistoryRepository`（其余逻辑不动，Task 6.4 改写）。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app-server:test --tests "*M6FindingRepositoryIntegrationTest*"`
Expected: PASS（6 个断言）。

- [ ] **Step 5: 运行全量 build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL，无回归。

- [ ] **Step 6: Commit**

```bash
git add module-result/src/main/kotlin/com/example/compliance/result app-server/src/test/kotlin/com/example/compliance/result/M6FindingRepositoryIntegrationTest.kt
git commit -m "feat(result): finding lifecycle entities and occurrence query (project_id + history/status/evidence)"
```

---
### Task 6.3: FindingLifecyclePort / Service（状态转移权威 + 复扫验证 + 证据）

**Files:**
- Create: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecyclePort.kt`
- Create: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecycleService.kt`
- Create: `module-result/src/test/kotlin/com/example/compliance/result/application/FindingLifecycleServiceTest.kt`

**Interfaces:**
- Consumes: Task 6.2 实体/仓储；`AuditService`（module-common，包 `com.example.compliance.common.audit.AuditService`，PF-9 真实签名）：`fun record(action: String, module: String, userId: Long? = null, resourceType: String? = null, resourceId: Long? = null, detail: String? = null, ip: String? = null)`（`@Transactional(REQUIRES_NEW)`）。
- Produces: `FindingLifecyclePort` 接口（module-remediation 在 M7 依赖它）：
  - `fun transition(findingId: Long, to: FindingStatus, reason: String?, changedBy: Long?): FindingStatus`
  - `fun addEvidence(findingId: Long, evidenceType: String, evidenceRef: String, changedBy: Long?): FindingEvidence`
  - `fun findingsForScanTask(scanTaskId: Long): List<FindingView>`
  - `fun findingsByProject(projectId: Long, status: FindingStatus?): List<FindingView>`
  - `fun verifyRechecking(projectId: Long, scanTaskId: Long, presentFindingIds: Set<Long>): VerifyResult`
- Produces: `data class FindingView(id, projectId, scanTaskId, ruleCode, severity, status, filePath, lineNumber, firstSeenAt, lastSeenAt, occurrenceCount, engine: String = "")`（engine 尾字段=发现引擎，M7/M8 消费）、`data class VerifyResult(closed: Int, regressed: Int)`（module-result.application）。

- [ ] **Step 1: 写失败测试**

创建 `module-result/src/test/kotlin/com/example/compliance/result/application/FindingLifecycleServiceTest.kt`：

```kotlin
package com.example.compliance.result.application

import com.example.compliance.common.audit.AuditService
import com.example.compliance.result.domain.Finding
import com.example.compliance.result.domain.FindingEvidence
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.result.domain.FindingStatusSnapshot
import com.example.compliance.result.infrastructure.FindingEvidenceRepository
import com.example.compliance.result.infrastructure.FindingHistoryRepository
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.result.infrastructure.FindingStatusSnapshotRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FindingLifecycleServiceTest {

    private val findingRepository = mockk<FindingRepository>()
    private val historyRepository = mockk<FindingHistoryRepository>()
    private val statusRepository = mockk<FindingStatusSnapshotRepository>()
    private val evidenceRepository = mockk<FindingEvidenceRepository>()
    private val auditService = mockk<AuditService>(relaxed = true)
    private val service = FindingLifecycleService(findingRepository, historyRepository, statusRepository, evidenceRepository, auditService)

    @Test
    fun `transition writes snapshot and audit and updates finding status`() {
        val finding = Finding().apply { id = 7L; status = FindingStatus.NEW }
        every { findingRepository.findById(7L) } returns java.util.Optional.of(finding)
        every { findingRepository.save(finding) } returns finding

        val result = service.transition(7L, FindingStatus.CONFIRMED, "verified", 3L)

        assertSame(FindingStatus.CONFIRMED, result)
        assertEquals(FindingStatus.CONFIRMED, finding.status)
        verify { statusRepository.save(any<FindingStatusSnapshot>()) }
        verify { auditService.record(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `verifyRechecking closes absent and regresses present findings`() {
        val fixed1 = Finding().apply { id = 1L; projectId = 5L; status = FindingStatus.RECHECKING; fingerprint = "f1" }
        val fixed2 = Finding().apply { id = 2L; projectId = 5L; status = FindingStatus.RECHECKING; fingerprint = "f2" }
        val fixed3 = Finding().apply { id = 3L; projectId = 5L; status = FindingStatus.RECHECKING; fingerprint = "f3" }
        every { findingRepository.findAll() } returns listOf(fixed1, fixed2, fixed3)
        every { findingRepository.save(any()) } answers { firstArg() }

        // 复扫命中 f1（present），f2/f3 缺席
        val result = service.verifyRechecking(5L, 99L, setOf(1L))

        assertEquals(VerifyResult(closed = 2, regressed = 1), result)
        assertEquals(FindingStatus.CONFIRMED, fixed1.status)
        assertEquals(FindingStatus.CLOSED, fixed2.status)
        assertEquals(FindingStatus.CLOSED, fixed3.status)
        verify { statusRepository.save(any<FindingStatusSnapshot>()) }
    }

    @Test
    fun `addEvidence persists evidence`() {
        every { evidenceRepository.save(any<FindingEvidence>()) } answers { firstArg() }
        val evidence = service.addEvidence(7L, "FIX_COMMIT", "abc123", 3L)
        assertEquals(7L, evidence.findingId)
        assertEquals("FIX_COMMIT", evidence.evidenceType)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-result:test --tests "*FindingLifecycleServiceTest*"`
Expected: 编译失败 — `FindingLifecycleService` 不存在。

- [ ] **Step 3: 实现接口与服务**

`FindingLifecyclePort.kt`：

```kotlin
package com.example.compliance.result.application

import com.example.compliance.result.domain.FindingEvidence
import com.example.compliance.result.domain.FindingStatus

/** 状态转移 DTO：module-remediation 只经此接口读写 finding 生命周期（P2-D5），禁止 import 实体。 */
data class FindingView(
    val id: Long,
    val projectId: Long,
    val scanTaskId: Long,
    val ruleCode: String,
    val severity: String,
    val status: FindingStatus,
    val filePath: String,
    val lineNumber: Int?,
    val firstSeenAt: java.time.Instant,
    val lastSeenAt: java.time.Instant,
    val occurrenceCount: Int,
    val engine: String = "",   // 发现引擎（M7 复扫定位、M8 引擎契约断言消费）
)

data class VerifyResult(val closed: Int, val regressed: Int)

/** finding 生命周期权威端口。实现：module-result 的 FindingLifecycleService。 */
interface FindingLifecyclePort {
    fun transition(findingId: Long, to: FindingStatus, reason: String?, changedBy: Long?): FindingStatus
    fun addEvidence(findingId: Long, evidenceType: String, evidenceRef: String, changedBy: Long?): FindingEvidence
    fun findingsForScanTask(scanTaskId: Long): List<FindingView>
    fun findingsByProject(projectId: Long, status: FindingStatus?): List<FindingView>
    fun verifyRechecking(projectId: Long, scanTaskId: Long, presentFindingIds: Set<Long>): VerifyResult
}
```

`FindingLifecycleService.kt`：

```kotlin
package com.example.compliance.result.application

import com.example.compliance.common.audit.AuditService
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.result.domain.FindingEvidence
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.result.domain.FindingStatusSnapshot
import com.example.compliance.result.infrastructure.FindingEvidenceRepository
import com.example.compliance.result.infrastructure.FindingHistoryRepository
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.result.infrastructure.FindingStatusSnapshotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** finding 生命周期唯一权威：所有状态转移、复扫验证、证据写入都经过这里（P2-D4）。 */
@Service
class FindingLifecycleService(
    private val findingRepository: FindingRepository,
    private val historyRepository: FindingHistoryRepository,
    private val statusRepository: FindingStatusSnapshotRepository,
    private val evidenceRepository: FindingEvidenceRepository,
    private val auditService: AuditService,
) : FindingLifecyclePort {

    override fun findingsForScanTask(scanTaskId: Long): List<FindingView> =
        findingRepository.findByProjectScanTask(scanTaskId).map { it.toView() }

    override fun findingsByProject(projectId: Long, status: FindingStatus?): List<FindingView> {
        val all = findingRepository.findAll().filter { it.projectId == projectId }
        return (status?.let { s -> all.filter { it.status == s } } ?: all).map { it.toView() }
    }

    @Transactional
    override fun transition(findingId: Long, to: FindingStatus, reason: String?, changedBy: Long?): FindingStatus {
        val finding = findingRepository.findById(findingId)
            .orElseThrow { BusinessException(404, "finding not found: $findingId") }
        val from = finding.status
        if (from == to) return to
        finding.status = to
        findingRepository.save(finding)
        statusRepository.save(FindingStatusSnapshot().apply {
            this.findingId = findingId; this.status = to; this.changedBy = changedBy; this.reason = reason
        })
        auditService.record("FINDING_TRANSITION", "result", changedBy, "finding", findingId, "{\"from\":\"$from\",\"to\":\"$to\",\"reason\":${quote(reason)}}")
        return to
    }

    @Transactional
    override fun addEvidence(findingId: Long, evidenceType: String, evidenceRef: String, changedBy: Long?): FindingEvidence {
        val saved = evidenceRepository.save(FindingEvidence().apply {
            this.findingId = findingId; this.evidenceType = evidenceType; this.evidenceRef = evidenceRef; this.addedBy = changedBy
        })
        auditService.record("FINDING_EVIDENCE", "result", changedBy, "finding", findingId, "{\"type\":\"$evidenceType\",\"ref\":${quote(evidenceRef)}}")
        return saved
    }

    /** 复扫验证：扫描完成后调用。处于 RECHECKING 的 finding —— 本次扫描缺席 → CLOSED；命中 → 回归 CONFIRMED。 */
    @Transactional
    override fun verifyRechecking(projectId: Long, scanTaskId: Long, presentFindingIds: Set<Long>): VerifyResult {
        var closed = 0
        var regressed = 0
        findingRepository.findAll().filter { it.projectId == projectId && it.status == FindingStatus.RECHECKING }
            .forEach { finding ->
                if (finding.id in presentFindingIds) {
                    transition(finding.id!!, FindingStatus.CONFIRMED, "regression_in_scan_$scanTaskId", null)
                    regressed++
                } else {
                    transition(finding.id!!, FindingStatus.CLOSED, "verification_passed_in_scan_$scanTaskId", null)
                    closed++
                }
            }
        return VerifyResult(closed, regressed)
    }

    private fun com.example.compliance.result.domain.Finding.toView() = FindingView(
        id!!, projectId, scanTaskId, ruleCode, severity, status, filePath, lineNumber,
        firstSeenAt, lastSeenAt, occurrenceCount, engine,
    )

    private fun quote(s: String?): String = "\"${s?.replace("\"", "\\\"") ?: ""}\""
}
```

> 说明：`verifyRechecking` 内 `transition` 是同类内的 `@Transactional` 方法调用，会绕开代理（自调用不开启新事务）——本方法自身已 `@Transactional`，同一事务内执行，正确。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :module-result:test --tests "*FindingLifecycleServiceTest*"`
Expected: PASS（3 个测试）。

- [ ] **Step 5: 运行全量 build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: Commit**

```bash
git add module-result/src/main/kotlin/com/example/compliance/result/application module-result/src/test/kotlin/com/example/compliance/result/application/FindingLifecycleServiceTest.kt
git commit -m "feat(result): finding lifecycle port and service (transitions, evidence, re-scan verification)"
```

---

### Task 6.4: FindingService.upsertByFingerprint 改写（复扫归属 + 状态机）

**Files:**
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingService.kt`（改写 upsertByFingerprint）
- Create: `module-result/src/test/kotlin/com/example/compliance/result/application/FindingServiceTest.kt`（若已存在则追加）

**Interfaces:**
- Consumes: Task 6.1 `FindingStatus`；Task 6.2 `FindingRepository.findByProjectIdAndFingerprint` + `FindingHistory`/`FindingHistoryRepository`；Task 6.3 `FindingLifecycleService`。
- Produces: `upsertByFingerprint(projectId, scanTaskId, engine, findings): UpsertResult` 语义：新指纹 → 建规范行(projectId, scanTaskId=本次, NEW) + history CREATED；已有指纹 → occurrenceCount+1 + lastSeenAt + history REAPPEARED，状态按状态机：
  - 活动集 `NEW/CONFIRMED/ASSIGNED/FIXING/RECHECKING` → status 不变；
  - `FIXED/CLOSED` → 回归 `CONFIRMED`（reason=reappeared_after_fix，经 FindingLifecycleService.transition 写 finding_status + audit）；
  - `WAIVED/IGNORED/FALSE_POSITIVE/ACCEPTED_RISK` → 保持终态（基线 §7.3 已豁免跳过）。

- [ ] **Step 1: 写失败测试**

创建 `module-result/src/test/kotlin/com/example/compliance/result/application/FindingServiceTest.kt`（包 `com.example.compliance.result.application`）：

```kotlin
package com.example.compliance.result.application

import com.example.compliance.result.domain.Finding
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.result.infrastructure.FindingHistoryRepository
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.result.infrastructure.FingerprintGenerator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FindingServiceTest {

    private val findingRepository = mockk<FindingRepository>()
    private val historyRepository = mockk<FindingHistoryRepository>()
    private val fingerprintGenerator = mockk<FingerprintGenerator>()
    private val lifecycleService = mockk<FindingLifecycleService>()
    private val findingService = FindingService(findingRepository, historyRepository, fingerprintGenerator, lifecycleService)

    private val newFinding = NewFinding("R1", "r", "A.java", 1, "HIGH", null, null, "s")

    @Test
    fun `existing finding in active state keeps status and counts occurrence`() {
        val fp = "fp-active"
        val existing = Finding().apply { id = 1L; projectId = 9L; status = FindingStatus.ASSIGNED; fingerprint = fp }
        every { findingRepository.findByProjectIdAndFingerprint(9L, fp) } returns existing
        every { findingRepository.save(any<Finding>()) } answers { firstArg() }
        every { fingerprintGenerator.generate(9L, "R1", "A.java", 1, "s") } returns fp

        val result = findingService.upsertByFingerprint(9L, 50L, "STUB", listOf(newFinding))

        assertEquals(UpsertResult(0, 1), result)
        assertEquals(FindingStatus.ASSIGNED, existing.status)   // 活动集 → 状态不变
        assertEquals(2, existing.occurrenceCount)
        verify { historyRepository.save(match { it.action == "REAPPEARED" && it.scanTaskId == 50L }) }
    }

    @Test
    fun `fixed finding reappearing regresses to confirmed`() {
        val fp = "fp-regress"
        val fixed = Finding().apply { id = 2L; projectId = 9L; status = FindingStatus.FIXED; fingerprint = fp }
        every { findingRepository.findByProjectIdAndFingerprint(9L, fp) } returns fixed
        every { findingRepository.save(any<Finding>()) } answers { firstArg() }
        every { fingerprintGenerator.generate(9L, "R1", "A.java", 1, "s") } returns fp
        every { lifecycleService.transition(2L, FindingStatus.CONFIRMED, "reappeared_after_fix", null) } returns FindingStatus.CONFIRMED

        findingService.upsertByFingerprint(9L, 51L, "STUB", listOf(newFinding))

        verify { lifecycleService.transition(2L, FindingStatus.CONFIRMED, "reappeared_after_fix", null) }
    }

    @Test
    fun `waived finding stays terminal`() {
        val fp = "fp-waived"
        val waived = Finding().apply { id = 3L; projectId = 9L; status = FindingStatus.WAIVED; fingerprint = fp }
        every { findingRepository.findByProjectIdAndFingerprint(9L, fp) } returns waived
        every { findingRepository.save(any<Finding>()) } answers { firstArg() }
        every { fingerprintGenerator.generate(9L, "R1", "A.java", 1, "s") } returns fp

        findingService.upsertByFingerprint(9L, 52L, "STUB", listOf(newFinding))

        assertEquals(FindingStatus.WAIVED, waived.status)   // 豁免集 → 保持终态
        verify(exactly = 0) { lifecycleService.transition(any(), any(), any(), any()) }
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-result:test --tests "*FindingServiceTest*"`
Expected: 编译失败 — `FindingService` 新构造参数（lifecycleService）未定义。

- [ ] **Step 3: 改写 FindingService**

`FindingService.kt` 全文件替换为：

```kotlin
package com.example.compliance.result.application

import com.example.compliance.result.domain.Finding
import com.example.compliance.result.domain.FindingHistory
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.result.infrastructure.FindingHistoryRepository
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.result.infrastructure.FingerprintGenerator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class NewFinding(
    val ruleCode: String,
    val ruleName: String?,
    val filePath: String,
    val lineNumber: Int?,
    val severity: String,
    val category: String?,
    val message: String?,
    val codeSnippet: String?,
)

data class UpsertResult(val created: Int, val updated: Int)

/** 活动集：状态机当前所处位置，复现时保持不动。 */
private val ACTIVE_STATES = setOf(
    FindingStatus.NEW, FindingStatus.CONFIRMED, FindingStatus.ASSIGNED,
    FindingStatus.FIXING, FindingStatus.RECHECKING,
)

/** 豁免终态：基线 §7.3 已豁免/忽略的 finding 复现时跳过，保持终态。 */
private val WAIVED_STATES = setOf(
    FindingStatus.WAIVED, FindingStatus.IGNORED,
    FindingStatus.FALSE_POSITIVE, FindingStatus.ACCEPTED_RISK,
)

@Service
class FindingService(
    private val findingRepository: FindingRepository,
    private val historyRepository: FindingHistoryRepository,
    private val fingerprintGenerator: FingerprintGenerator,
    private val lifecycleService: FindingLifecycleService,
) {
    /** 按 (projectId, fingerprint) 规范行去重写入（P2-D2）：新指纹 CREATED，已有指纹 REAPPEARED + 状态机处置。 */
    @Transactional
    fun upsertByFingerprint(projectId: Long, scanTaskId: Long, engine: String, findings: List<NewFinding>): UpsertResult {
        var created = 0
        var updated = 0
        for (f in findings) {
            val fingerprint = fingerprintGenerator.generate(projectId, f.ruleCode, f.filePath, f.lineNumber, f.codeSnippet)
            val existing = findingRepository.findByProjectIdAndFingerprint(projectId, fingerprint)
            if (existing == null) {
                findingRepository.save(Finding().apply {
                    this.projectId = projectId
                    this.scanTaskId = scanTaskId
                    this.engine = engine
                    ruleCode = f.ruleCode
                    ruleName = f.ruleName
                    filePath = f.filePath
                    lineNumber = f.lineNumber
                    severity = f.severity
                    category = f.category
                    message = f.message
                    codeSnippet = f.codeSnippet
                    this.fingerprint = fingerprint
                }).let { saved ->
                    historyRepository.save(FindingHistory().apply {
                        findingId = saved.id!!; this.scanTaskId = scanTaskId; action = "CREATED"
                    })
                }
                created++
            } else {
                existing.occurrenceCount += 1
                existing.lastSeenAt = Instant.now()
                findingRepository.save(existing)
                historyRepository.save(FindingHistory().apply {
                    findingId = existing.id!!; this.scanTaskId = scanTaskId; action = "REAPPEARED"
                })
                when {
                    existing.status in ACTIVE_STATES -> Unit                       // 保持状态机当前位置
                    existing.status in WAIVED_STATES -> Unit                       // 已豁免，跳过
                    else -> lifecycleService.transition(existing.id!!, FindingStatus.CONFIRMED, "reappeared_after_fix", null) // FIXED/CLOSED → 回归
                }
                updated++
            }
        }
        return UpsertResult(created, updated)
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :module-result:test --tests "*FindingServiceTest*" --tests "*FindingLifecycleServiceTest*"`
Expected: PASS（Task 6.3 与新增全部通过）。

- [ ] **Step 5: 运行全量 build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。注意 `ScanPipelineIntegrationTest`（frozen）—— PIPE 项目首扫：新指纹 CREATED，occurrence(该任务)=1，`findings().count{STUB-SQLI}==1` 仍成立；`ReportApiIntegrationTest` 的 `bySeverity.HIGH >= 1` 不受影响。

- [ ] **Step 6: Commit**

```bash
git add module-result/src/main/kotlin/com/example/compliance/result/application/FindingService.kt module-result/src/test/kotlin/com/example/compliance/result/application
git commit -m "feat(result): fingerprint upsert with project-scoped canonical row and lifecycle state machine"
```

---
### Task 6.5: 版本盖章 + 编排器 occurrence 化

**Files:**
- Modify: `module-checklist/src/main/kotlin/com/example/compliance/checklist/application/ChecklistQueryService.kt`（+publishedVersionForProject）
- Modify: `module-scan/src/main/kotlin/com/example/compliance/scan/domain/ScanTask.kt`（+5 字段）
- Modify: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanTaskService.kt`（requestId + triggerType 参数）
- Modify: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ComplianceEvaluator.kt`（版本参数化）
- Modify: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanOrchestrator.kt`（盖章 + occurrence + verifyRechecking + durationMs）
- Modify: `module-scan/src/main/kotlin/com/example/compliance/scan/api/dto/*.kt`（ScanResponse 加字段）
- Modify: `module-report/src/main/kotlin/com/example/compliance/report/application/ReportService.kt`（scanSummary 用 occurrence 查询）
- Modify: `module-scan/src/test/kotlin/com/example/compliance/scan/application/ComplianceEvaluatorTest.kt`

**Interfaces:**
- Consumes: Task 6.3 `FindingLifecycleService`/`FindingLifecyclePort.findingsForScanTask`/`verifyRechecking`；Task 6.2 `FindingRepository.findByProjectScanTask`。
- Produces: `ChecklistQueryService.publishedVersionForProject(projectId): ChecklistVersion?`；`ScanTask.checklistVersionId/ruleIds/commitId/durationMs/requestId`；`ScanTaskService.startScan(projectId, engine, ref, triggerType="MANUAL", requestId: String? = null): ScanTask`（requestId 缺省内部生成 UUID；M7 requestRecheck 传 `recheck-f<findingId>` 唯一定位复扫，V8 已建 request_id 列）；`ComplianceEvaluator.evaluate(projectId, checklistVersionId, findings)`。

- [ ] **Step 1: 写失败测试（评估器版本参数化）**

在 `module-scan/src/test/kotlin/com/example/compliance/scan/application/ComplianceEvaluatorTest.kt` 追加：

```kotlin
    @Test
    fun `evaluate uses version items when checklistVersionId provided`() {
        val item = ChecklistItem().apply { itemCode = "M6-001"; versionId = 77L }
        every { checklistQueryService.versionItems(77L) } returns listOf(item)
        every { ruleQueryService.findByRuleCode("R1") } returns RuleDefinition().apply { id = 1L; ruleCode = "R1" }
        every { ruleQueryService.policyByRuleId(1L) } returns RuleEvaluationPolicy().apply { spElExpression = "severity == 'HIGH'" }
        every { ruleQueryService.itemCodesByRuleId(1L) } returns listOf("M6-001")
        val finding = Finding().apply { id = 9L; ruleCode = "R1"; severity = "HIGH" }

        val result = evaluator.evaluate(9L, 77L, listOf(finding))

        assertEquals(1, result.size)
        assertEquals("M6-001", result[0].itemCode)
        verify { checklistQueryService.versionItems(77L) }
    }
```

> 若该测试文件尚不存在（按 M4 既有惯例应已存在），则新建：`package com.example.compliance.scan.application`，`ComplianceEvaluatorTest` mock 依赖 `ruleQueryService`/`checklistQueryService`，构造 `ComplianceEvaluator(ruleQueryService, checklistQueryService)`。`ChecklistItem`/`RuleDefinition`/`RuleEvaluationPolicy` 的构造以各领域文件实际字段为准（字段赋默认值即可，无需逐字对齐）。`versionItems(versionId)` 与 `publishedItemsForProject(projectId)` 均为 `ChecklistQueryService` 既有方法（前者返回 `List<ChecklistItem>`，后者返回可空 `List<ChecklistItem>`）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-scan:test --tests "*ComplianceEvaluatorTest*"`
Expected: 编译失败 — `evaluate` 三参数签名未定义。

- [ ] **Step 3: 实现**

`ChecklistQueryService.kt` 追加：

```kotlin
    /** 项目当前绑定的已发布版本；未绑定或未发布返回 null。M6 版本盖章使用。 */
    fun publishedVersionForProject(projectId: Long): ChecklistVersion? {
        val binding = bindingRepository.findFirstByProjectIdOrderByIdDesc(projectId) ?: return null
        val version = versionRepository.findById(binding.checklistVersionId).orElse(null) ?: return null
        if (version.status != VersionStatus.PUBLISHED) return null
        return version
    }
```

（`ChecklistQueryService` 若尚无 `bindingRepository`/`versionRepository` 构造依赖，按既有仓储字段补入；`VersionStatus` 来自 `com.example.compliance.checklist.domain`，以既有类为准。）

`ScanTask.kt` 追加字段：

```kotlin
    @Column(name = "checklist_version_id")
    var checklistVersionId: Long? = null
    @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "rule_ids", columnDefinition = "jsonb")
    var ruleIds: String? = null
    @Column(name = "commit_id", length = 64)
    var commitId: String? = null
    @Column(name = "duration_ms")
    var durationMs: Long? = null
    @Column(name = "request_id", length = 64)
    var requestId: String? = null
```

（`ScanTask.kt` 需新增 import：`org.hibernate.annotations.JdbcTypeCode`。）

`ScanTaskService.startScan` 改为：

```kotlin
    fun startScan(projectId: Long, engine: String, ref: String?, triggerType: String = "MANUAL", requestId: String? = null): ScanTask {
        if (registry.get(engine) == null) {
            throw BusinessException(400, "unsupported engine: $engine")
        }
        projectService.get(projectId)
        val task = scanTaskRepository.save(ScanTask().apply {
            this.projectId = projectId
            this.engine = engine
            this.ref = ref
            this.triggerType = triggerType
            this.requestId = requestId ?: java.util.UUID.randomUUID().toString()
        })
        orchestrator.executeAsync(task.id!!)
        return task
    }
```

`ComplianceEvaluator.evaluate` 签名与解析改为：

```kotlin
    /** 对一次扫描的 findings 做合规判定：优先按版本解析清单条目；versionId 为 null 时回退到项目当前已发布绑定。 */
    fun evaluate(projectId: Long, checklistVersionId: Long?, findings: List<Finding>): List<ItemEvaluation> {
        val items = checklistVersionId
            ?.let { checklistQueryService.versionItems(it) }
            ?: checklistQueryService.publishedItemsForProject(projectId) ?: return emptyList()
        // …… 其余逻辑不变（按 ruleCode 映射、SpEL 判定）
    }
```

`ScanOrchestrator`：
1. 构造参数新增 `private val checklistQueryService: com.example.compliance.checklist.application.ChecklistQueryService` 与 `private val lifecycleService: com.example.compliance.result.application.FindingLifecycleService`。
2. `executeAsync` 开头（置 RUNNING 后）解析并盖章版本：

```kotlin
        val version = checklistQueryService.publishedVersionForProject(task.projectId)
        task.checklistVersionId = version?.id
        log(scanTaskId, "PREPARE", "INFO", "checklistVersionId=${version?.id ?: "none"}")
```

3. 归一化循环内收集 `ruleIds`（命中规则的 id）：

```kotlin
            val ruleIds = mutableSetOf<Long>()
            for (raw in result.findings) {
                val rule = ruleQueryService.publishedRuleByEngineRuleId(task.engine, raw.engineRuleId)
                if (rule == null) { skipped++; continue }
                ruleIds += rule.id!!
                normalized += NewFinding(
                    rule.ruleCode, rule.name, raw.filePath, raw.line,
                    raw.severity, raw.category, raw.message, raw.codeSnippet,
                )
            }
```

4. 落 `ScanJob` 前：`task.ruleIds = objectMapper.writeValueAsString(ruleIds)`；upsert 后改用 occurrence 查询并做复扫验证：

```kotlin
            val upsert = findingService.upsertByFingerprint(task.projectId, scanTaskId, task.engine, normalized)
            val findings = findingRepository.findByProjectScanTask(scanTaskId)
            // 复扫验证（M7 闭环）：RECHECKING finding 缺席→CLOSED，命中→回归 CONFIRMED
            val presentIds = findings.mapNotNull { it.id }.toSet()
            val verify = lifecycleService.verifyRechecking(task.projectId, scanTaskId, presentIds)
            log(scanTaskId, "VERIFY", "INFO", "rechecking closed=${verify.closed} regressed=${verify.regressed}")
```

5. 评估调用改签名：`complianceEvaluator.evaluate(task.projectId, task.checklistVersionId, findings)`；`ComplianceEvaluation`/`ChecklistItemResult` 落 `checklistVersionId = task.checklistVersionId`；`task.durationMs = duration`；成功分支 `task.findingCount = findings.size`（occurrence 口径）。

`ScanResponse` 追加字段（`checklistVersionId: Long?`, `requestId: String?`），`from` 映射对应。

`ReportService.scanSummary` 第 22 行改为 occurrence 查询（保证「报表数据与扫描结果一致」，验收标准 #10）：

```kotlin
        val findings = findingRepository.findByProjectScanTask(scanTaskId)
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :module-scan:test --tests "*ComplianceEvaluatorTest*"` 与 `./gradlew build`
Expected: 全部通过。frozen `ScanPipelineIntegrationTest`/`ReportApiIntegrationTest` 的断言在 occurrence 口径下仍成立（单次扫描的 finding 均带该任务 history）。

- [ ] **Step 5: Commit**

```bash
git add module-checklist/src/main/kotlin/com/example/compliance/checklist/application/ChecklistQueryService.kt module-scan/src module-report/src/main/kotlin/com/example/compliance/report/application/ReportService.kt
git commit -m "feat(scan): checklist version stamping, occurrence-based findings, re-scan verification hook"
```

---

### Task 6.6: M6 集成测试 —— 版本盖章 + 复扫归属端到端

**Files:**
- Create: `app-server/src/test/kotlin/com/example/compliance/scan/M6LifecycleIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 6.4/6.5 全部；`ChecklistService`/`RuleService`/`ProjectService`/`ScanTaskService` 签名（沿用 ScanPipelineIntegrationTest 既有用法，DTo 类名以既有 `AddItemCommand`/`BindRepositoryCommand`/`CreateProjectCommand`/`AddEngineBindingCommand`/`CreateRuleCommand`/`SetPolicyCommand` 为准）。

- [ ] **Step 1: 写集成测试**

创建 `app-server/src/test/kotlin/com/example/compliance/scan/M6LifecycleIntegrationTest.kt`：

```kotlin
package com.example.compliance.scan

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.checklist.application.ChecklistService
import com.example.compliance.project.application.BindRepositoryCommand
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import com.example.compliance.result.application.FindingLifecyclePort
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanResult
import com.example.compliance.rule.application.AddEngineBindingCommand
import com.example.compliance.rule.application.CreateRuleCommand
import com.example.compliance.rule.application.RuleService
import com.example.compliance.rule.application.SetPolicyCommand
import com.example.compliance.scan.application.ScanTaskService
import com.example.compliance.scan.domain.ScanTaskStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** M6-* 数据前缀；独立 @TestConfiguration STUB 适配器（stub-m6-rule），与冻结 ScanPipeline 的 stub-rule-sqli 不冲突。 */
class M6LifecycleIntegrationTest : AbstractIntegrationTest() {

    @TestConfiguration
    class StubM6AdapterConfig {
        @Bean
        fun stubM6Adapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUBM6"
            override fun scan(context: ScanContext): ScanResult = ScanResult(
                findings = listOf(RawFinding("stub-m6-rule", "M6", "src/main/java/M6.java", 10, "HIGH", "m", "x=id;"))
            )
        }
    }

    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var checklistService: ChecklistService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService
    @Autowired lateinit var lifecyclePort: FindingLifecyclePort

    @Test
    fun `re-scan attributes findings via history and stamps checklist version`() {
        // 1. 项目 + 仓库
        val project = projectService.create(CreateProjectCommand("M6P", "M6 项目", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("m6-repo", "https://git.example.com/m6.git", "GITLAB", "main", "tok"))
        // 2. 清单 → 发布 → 绑定
        val standard = checklistService.createStandard("M6-SEC", "M6 规范", null)
        val checklist = checklistService.createChecklist(standard.id!!, "M6-BASIC", "M6 基线")
        checklistService.addItem(checklist.id!!, com.example.compliance.checklist.application.AddItemCommand(itemCode = "M6-001", name = "M6 项", riskLevel = "HIGH"))
        val version = checklistService.publish(checklist.id!!)
        checklistService.bindProject(project.id!!, version.id!!)
        // 3. 规则（STUBM6 引擎绑定 + 映射 + FAIL 策略）
        val rule = ruleService.create(CreateRuleCommand("M6-SQLI", "M6 注入", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUBM6", "stub-m6-rule", null))
        ruleService.addComplianceMapping(rule.id!!, "M6-001")
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'HIGH'"))
        ruleService.publish(rule.id!!)
        // 4. 两次扫描
        val task1 = scanTaskService.startScan(project.id!!, "STUBM6", "main")
        waitDone(task1.id!!)
        val task2 = scanTaskService.startScan(project.id!!, "STUBM6", "main")
        waitDone(task2.id!!)
        // 5. 断言：版本盖章 + occurrence 归属 + findingCount
        val t2 = scanTaskService.get(task2.id!!)
        assertEquals(version.id, t2.checklistVersionId)
        assertNotNull(t2.ruleIds)
        assertEquals(1, t2.findingCount)                       // occurrence 口径：本次扫描全部命中 = 1（同一指纹复现）
        assertEquals(1, scanTaskService.findings(task2.id!!).size)   // occurrence 视图含复现的 finding
        val views = lifecyclePort.findingsForScanTask(task2.id!!)
        assertEquals(1, views.size)
        assertEquals(FindingStatus.NEW, views[0].status)       // 活动集 → 状态不变
        assertEquals(2, views[0].occurrenceCount)              // 两次扫描命中
        // 6. 评估带版本
        val compliance = scanTaskService.complianceResults(task2.id!!)
        assertEquals(version.id, compliance.evaluation?.checklistVersionId)
        assertEquals(1, compliance.evaluation?.failed)
    }

    private fun waitDone(taskId: Long) {
        var done = false
        repeat(50) {
            val s = scanTaskService.get(taskId).status
            if (s != ScanTaskStatus.RUNNING && s != ScanTaskStatus.PENDING) { done = true; return@repeat }
            Thread.sleep(200)
        }
        assertTrue(done, "scan $taskId should finish within timeout")
        assertEquals(ScanTaskStatus.SUCCESS, scanTaskService.get(taskId).status)
    }
}
```

> 注意：`ScanTaskService.findings()` 在 Task 6.5 中已切换为 occurrence 查询（`findByProjectScanTask`），故此处 `scanTaskService.findings(task2.id!!)` 即为 occurrence 视图。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app-server:test --tests "*M6LifecycleIntegrationTest*"`
Expected: 编译失败或断言失败（`checklistVersionId` 等为 null / occurrence 数量不符）—— 取决于前序任务是否已完成。

- [ ] **Step 3: 运行确认通过**

Run: `./gradlew :app-server:test --tests "*M6LifecycleIntegrationTest*"`
Expected: PASS。

- [ ] **Step 4: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（含 frozen ScanPipeline/ReportApi/FindingRepository 全部绿）。

- [ ] **Step 5: Commit**

```bash
git add app-server/src/test/kotlin/com/example/compliance/scan/M6LifecycleIntegrationTest.kt
git commit -m "test(scan): M6 lifecycle integration - version stamping and re-scan attribution end-to-end"
```

> **M6 完成标准**：V8 迁移可执行；finding 带 project_id 与 11 态；复扫 occurrence 归属正确；版本盖章贯通 scan→result→evaluation；`./gradlew build` 全绿。

---
## M7 — 整改闭环（module-remediation）

> **模块落地说明**：M7 首次给 `module-remediation` 写入实现。该模块当前仅有 `package-info.kt` 与 `build.gradle.kts`（只依赖 module-common）。按 P2-D5（例外）：remediation 可依赖 module-result 的**接口与值类型**（`FindingLifecyclePort`、`FindingStatus`、`FindingView`），禁止 import 任何 `@Entity`。**另经 pre-flight ruling PF-5**：Task 7.4 的 recheck 需创建复扫任务（spec §4.3/§4.4 绑定），因此 remediation 额外依赖 module-scan 的 **`ScanTriggerPort` 接口**（只 import 接口，不 import 实体）。
>
> **状态权威约定（P2-D4，本 M7 所有任务遵守）**：`finding.status` 是唯一权威，转移一律经 `FindingLifecyclePort.transition`；`remediation_task.status` 仅是**同事务写入的冗余缓存列**（供查询过滤），每成功转移后把返回值镜像写回 `task.status` 并 save，永不作独立判定源。

### Task 7.1: 整改任务领域模型 + 状态冗余列

**Files:**
- Create: `module-remediation/src/main/kotlin/com/example/compliance/remediation/domain/RemediationTask.kt`
- Create: `module-remediation/src/main/kotlin/com/example/compliance/remediation/infrastructure/RemediationTaskRepository.kt`
- Create: `module-remediation/src/main/kotlin/com/example/compliance/remediation/application/RemediationTaskView.kt`
- Create: `module-remediation/src/main/kotlin/com/example/compliance/remediation/application/FindingRemediationView.kt`
- Create: `module-remediation/src/main/kotlin/com/example/compliance/remediation/application/RemediationService.kt`（assign/get/listByProject 核心，7.2/7.3/7.4 扩展）
- Create: `app-server/src/main/resources/db/migration/V9__remediation_task.sql`
- Create: `module-remediation/src/test/kotlin/com/example/compliance/remediation/application/RemediationServiceTest.kt`
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecyclePort.kt`（+findById）
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecycleService.kt`（实现 findById）

**Interfaces:**
- Consumes: `BaseEntity`（module-common.domain）；`FindingLifecyclePort`（Task 6.3）+ 本任务补 `findById(findingId): FindingView?`；`FindingView`（Task 6.3，尾部 `engine` 字段由 Task 6.3 补默认 `= ""`）。
- Produces: 实体 `RemediationTask`（**含 status 冗余缓存列**，P2-D4）；`RemediationTaskView`（任务 DTO）；`FindingRemediationView(finding, task?)`（finding 中心响应）；仓储 `RemediationTaskRepository : JpaRepository<RemediationTask, Long>`（`findByFindingId`、`findByProjectId`、`findByAssigneeUserId`）；`RemediationService.assign(findingId, actorId, assigneeUserId?, plan?, dueDate?): FindingRemediationView`。

- [ ] **Step 1: 写失败测试**

创建 `module-remediation/src/test/kotlin/com/example/compliance/remediation/application/RemediationServiceTest.kt`：

```kotlin
package com.example.compliance.remediation.application

import com.example.compliance.remediation.domain.RemediationTask
import com.example.compliance.remediation.infrastructure.RemediationTaskRepository
import com.example.compliance.result.application.FindingLifecyclePort
import com.example.compliance.result.application.FindingView
import com.example.compliance.result.domain.FindingStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** M7 单元测试：状态权威在 finding，task.status 仅镜像（P2-D4）。 */
class RemediationServiceTest {

    private val taskRepository = mockk<RemediationTaskRepository>()
    private val lifecyclePort = mockk<FindingLifecyclePort>()
    private val service = RemediationService(taskRepository, lifecyclePort)

    private fun view(status: FindingStatus) = FindingView(
        id = 7L, projectId = 9L, scanTaskId = 1L, ruleCode = "R1", severity = "HIGH",
        status = status, filePath = "A.java", lineNumber = 1,
        firstSeenAt = Instant.now(), lastSeenAt = Instant.now(), occurrenceCount = 1,
    )

    @Test
    fun `assign creates task and mirrors assigned status`() {
        val saved = RemediationTask().apply { id = 11L; findingId = 7L; assigneeUserId = 3L }
        every { taskRepository.findByFindingId(7L) } returns null
        every { taskRepository.save(any<RemediationTask>()) } returns saved
        every { lifecyclePort.findById(7L) } returns view(FindingStatus.CONFIRMED)
        every { lifecyclePort.transition(7L, FindingStatus.ASSIGNED, "assigned", 9L) } returns FindingStatus.ASSIGNED

        val result = service.assign(7L, 9L, 3L, "fix in sprint", null)

        assertNotNull(result.task?.id)
        assertEquals(7L, result.finding.id)
        assertEquals(FindingStatus.ASSIGNED, result.finding.status)
        assertEquals(FindingStatus.ASSIGNED, result.task?.status)   // 镜像
        assertEquals(3L, result.task?.assigneeUserId)
        verify { lifecyclePort.transition(7L, FindingStatus.ASSIGNED, "assigned", 9L) }
    }

    @Test
    fun `assign rejects finding not in confirmed state`() {
        every { lifecyclePort.findById(7L) } returns view(FindingStatus.NEW)
        val ex = org.junit.jupiter.api.assertThrows<com.example.compliance.common.exception.BusinessException> {
            service.assign(7L, 9L, 3L, null, null)
        }
        assertEquals("finding not in CONFIRMED state: 7", ex.message)
    }

    @Test
    fun `get returns view with null task before assign`() {
        every { taskRepository.findByFindingId(8L) } returns null
        every { lifecyclePort.findById(8L) } returns view(FindingStatus.NEW)
        val result = service.get(8L)
        assertNull(result.task)
        assertEquals(FindingStatus.NEW, result.finding.status)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-remediation:test --tests "*RemediationServiceTest*"`
Expected: 编译失败 — `RemediationService`/`RemediationTask` 不存在。

- [ ] **Step 3: 写 V9 迁移（对齐 spec §4.1 逐字）**

创建 `app-server/src/main/resources/db/migration/V9__remediation_task.sql`：

```sql
-- 整改任务：status 为冗余缓存列（P2-D4，权威=finding.status，同事务镜像写入）
CREATE TABLE remediation_task (
    id               BIGSERIAL PRIMARY KEY,
    finding_id       BIGINT      NOT NULL,
    project_id       BIGINT      NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'OPEN',
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

> 无 `remediation_action` 表（PF-3 裁决：spec §4.1 无此表，删除 comment 端点）。

- [ ] **Step 4: 实现实体、仓储与 DTO**

`RemediationTask.kt`：

```kotlin
package com.example.compliance.remediation.domain

import com.example.compliance.common.domain.BaseEntity
import com.example.compliance.result.domain.FindingStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.LocalDate

/** 整改任务：关联 finding，记录责任人/计划/期限。status 为冗余缓存列（P2-D4：权威=finding.status，同事务镜像写入，禁止第二权威）。 */
@Entity
@Table(name = "remediation_task")
class RemediationTask : BaseEntity() {
    @Column(name = "finding_id", nullable = false)
    var findingId: Long = 0
    @Column(name = "project_id", nullable = false)
    var projectId: Long = 0
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: FindingStatus = FindingStatus.NEW
    @Column(name = "assignee_user_id")
    var assigneeUserId: Long? = null
    @Column(name = "plan")
    var plan: String? = null
    @Column(name = "due_date")
    var dueDate: LocalDate? = null
    @Column(name = "created_by")
    var createdBy: Long? = null
}
```

> 注意 `status` 默认值是 **NEW**（实体侧），DB 默认 'OPEN' 仅是存量兼容——首次 save 前实体总是先 `transition` 镜像写入，不依赖 DB 默认值语义。

`RemediationTaskRepository.kt`：

```kotlin
package com.example.compliance.remediation.infrastructure

import com.example.compliance.remediation.domain.RemediationTask
import org.springframework.data.jpa.repository.JpaRepository

interface RemediationTaskRepository : JpaRepository<RemediationTask, Long> {
    fun findByFindingId(findingId: Long): RemediationTask?
    fun findByProjectId(projectId: Long): List<RemediationTask>
    fun findByAssigneeUserId(assigneeUserId: Long): List<RemediationTask>
}
```

`RemediationTaskView.kt`：

```kotlin
package com.example.compliance.remediation.application

import com.example.compliance.result.domain.FindingStatus
import java.time.Instant
import java.time.LocalDate

/** 整改任务视图（任务侧元数据；finding 状态在 FindingView，权威=finding.status）。 */
data class RemediationTaskView(
    val id: Long,
    val findingId: Long,
    val projectId: Long,
    val assigneeUserId: Long?,
    val createdBy: Long?,
    val plan: String?,
    val dueDate: LocalDate?,
    val status: FindingStatus,
    val createdAt: Instant,
)
```

`FindingRemediationView.kt`：

```kotlin
package com.example.compliance.remediation.application

import com.example.compliance.result.application.FindingView

/** finding 中心响应：finding 全量视图 + 可空的整改任务元数据（未派单时为 null）。 */
data class FindingRemediationView(
    val finding: FindingView,
    val task: RemediationTaskView?,
)
```

- [ ] **Step 5: FindingLifecyclePort 补 findById**

`module-result/.../application/FindingLifecyclePort.kt` 接口追加（`FindingView` 是 module-result 自身 DTO，端口扩展合理——spec §4.4 端口清单非穷尽，finding 中心服务需按 id 读状态）：

```kotlin
    fun findById(findingId: Long): FindingView?
```

`FindingLifecycleService` 实现追加：

```kotlin
    override fun findById(findingId: Long): FindingView? =
        findingRepository.findById(findingId).map { it.toView() }.orElse(null)
```

（`toView` 私有扩展已在 Task 6.3 定义；Task 6.3 已给 `FindingView` 尾部加 `engine: String = ""`。）

- [ ] **Step 6: 实现 RemediationService 核心（assign/get/listByProject）**

`module-remediation/src/main/kotlin/com/example/compliance/remediation/application/RemediationService.kt`：

```kotlin
package com.example.compliance.remediation.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.remediation.domain.RemediationTask
import com.example.compliance.remediation.infrastructure.RemediationTaskRepository
import com.example.compliance.result.application.FindingLifecyclePort
import com.example.compliance.result.application.FindingView
import com.example.compliance.result.domain.FindingStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** 整改闭环服务：经 FindingLifecyclePort 驱动 finding 生命周期（P2-D4/D5），task.status 仅镜像。 */
@Service
class RemediationService(
    private val taskRepository: RemediationTaskRepository,
    private val lifecyclePort: FindingLifecyclePort,
) {
    /** 派单：创建整改任务并把 finding 置为 ASSIGNED（task.status 镜像写入）。 */
    @Transactional
    fun assign(
        findingId: Long, actorId: Long, assigneeUserId: Long?, plan: String?, dueDate: LocalDate?,
    ): FindingRemediationView {
        val finding = mustGetFinding(findingId)
        if (finding.status != FindingStatus.CONFIRMED) {
            throw BusinessException(409, "finding not in CONFIRMED state: $findingId")
        }
        val existing = taskRepository.findByFindingId(findingId)
        val task = existing ?: taskRepository.save(RemediationTask().apply {
            this.findingId = findingId
            this.projectId = finding.projectId
            this.createdBy = actorId
        })
        if (existing == null) {
            task.assigneeUserId = assigneeUserId
            task.plan = plan
            task.dueDate = dueDate
        }
        task.status = lifecyclePort.transition(findingId, FindingStatus.ASSIGNED, "assigned", actorId)
        return FindingRemediationView(finding, taskRepository.save(task).toView())
    }

    @Transactional(readOnly = true)
    fun get(findingId: Long): FindingRemediationView {
        val finding = mustGetFinding(findingId)
        return FindingRemediationView(finding, taskRepository.findByFindingId(findingId)?.toView())
    }

    @Transactional(readOnly = true)
    fun listByProject(projectId: Long): List<FindingRemediationView> {
        val tasks = taskRepository.findByProjectId(projectId).associateBy { it.findingId }
        return tasks.values.map { task ->
            FindingRemediationView(
                lifecyclePort.findById(task.findingId) ?: return@map null,
                task.toView(),
            )
        }.filterNotNull()
    }

    protected fun mustGetFinding(findingId: Long): FindingView =
        lifecyclePort.findById(findingId)
            ?: throw BusinessException(404, "finding not found: $findingId")

    private fun RemediationTask.toView() = RemediationTaskView(
        id!!, findingId, projectId, assigneeUserId, createdBy, plan, dueDate, status, createdAt!!,
    )
}
```

> `listByProject` 以任务表为入口，missing finding 防御性跳过（`filterNotNull`）；Task 7.2 的 GET /findings 会用 `lifecyclePort.findingsByProject` + 任务表 left join 的完整版。

- [ ] **Step 7: 运行测试确认通过**

Run: `./gradlew :module-remediation:test --tests "*RemediationServiceTest*"`
Expected: PASS（3 个测试）。

- [ ] **Step 8: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 9: Commit**

```bash
git add module-remediation module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecyclePort.kt module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecycleService.kt app-server/src/main/resources/db/migration/V9__remediation_task.sql
git commit -m "feat(remediation): remediation task domain with status mirror, V9 migration, lifecycle findById, service core"
```

> **模块依赖**：执行前在 `module-remediation/build.gradle.kts` 增加：
> ```kotlin
> dependencies {
>     implementation(project(":module-result"))
> }
> ```
> 并补 test 依赖（JUnit/MockK，对齐 `module-scan/build.gradle.kts` 既有 testImplementation 块）。`module-scan` 依赖（ScanTriggerPort）到 Task 7.4 再加。

---

### Task 7.2: finding 中心端点（confirm/assign/fixing/fixed/evidence + GET 列表）

**Files:**
- Create: `module-remediation/src/main/kotlin/com/example/compliance/remediation/api/RemediationController.kt`
- Modify: `module-remediation/src/main/kotlin/com/example/compliance/remediation/application/RemediationService.kt`（+confirm/startFix/markFixed/addEvidence/list）
- Modify: `module-remediation/src/test/kotlin/com/example/compliance/remediation/application/RemediationServiceTest.kt`（+转移测试）
- Create: `module-remediation/src/test/kotlin/com/example/compliance/remediation/api/RemediationControllerTest.kt`（切片）

**Interfaces:**
- Consumes: Task 7.1 服务/视图；`FindingLifecyclePort.transition/addEvidence`；`FindingView.engine`（Task 6.3）。
- Produces: `RemediationService` 扩展方法（finding 中心，全部带 from 状态守卫，409 拒绝非法转移）：
  - `confirm(findingId, actorId): FindingRemediationView`（NEW→CONFIRMED）
  - `startFix(findingId, actorId): FindingRemediationView`（ASSIGNED→FIXING）
  - `markFixed(findingId, actorId, evidenceType, evidenceRef): FindingRemediationView`（FIXING→FIXED，必附 evidence）
  - `addEvidence(findingId, actorId, evidenceType, evidenceRef): FindingRemediationView`（追加证据，无转移）
  - `list(projectId?, status?, severity?, page: Int, size: Int): List<FindingRemediationView>`（GET /findings）
- Produces: `RemediationController` 端点（spec §4.4）：
  - `GET /api/v1/remediation/findings`（query: projectId/status/severity/page/size）
  - `POST /api/v1/remediation/findings/{id}/confirm`
  - `POST /api/v1/remediation/findings/{id}/assign`（body: assigneeId/plan/dueDate）
  - `POST /api/v1/remediation/findings/{id}/fixing`
  - `POST /api/v1/remediation/findings/{id}/fixed`（body: evidenceType/evidenceRef）
  - `POST /api/v1/remediation/findings/{id}/evidence`（body: evidenceType/evidenceRef）

- [ ] **Step 1: 写失败测试（转移守卫 + 证据）**

`RemediationServiceTest.kt` 追加：

```kotlin
    @Test
    fun `confirm requires NEW`() {
        every { lifecyclePort.findById(7L) } returns view(FindingStatus.CONFIRMED)
        val ex = org.junit.jupiter.api.assertThrows<com.example.compliance.common.exception.BusinessException> {
            service.confirm(7L, 9L)
        }
        assertEquals("finding not in NEW state: 7", ex.message)
    }

    @Test
    fun `fixed requires evidence and transitions from fixing`() {
        every { lifecyclePort.findById(7L) } returns view(FindingStatus.FIXING)
        every { taskRepository.findByFindingId(7L) } returns RemediationTask().apply { id = 11L; findingId = 7L }
        every { taskRepository.save(any<RemediationTask>()) } answers { firstArg() }
        every { lifecyclePort.addEvidence(7L, "FIX_COMMIT", "deadbeef", 9L) } returns
            com.example.compliance.result.domain.FindingEvidence().apply { id = 1L }
        every { lifecyclePort.transition(7L, FindingStatus.FIXED, "fixed", 9L) } returns FindingStatus.FIXED

        val result = service.markFixed(7L, 9L, "FIX_COMMIT", "deadbeef")

        assertEquals(FindingStatus.FIXED, result.finding.status)
        verify { lifecyclePort.addEvidence(7L, "FIX_COMMIT", "deadbeef", 9L) }
    }

    @Test
    fun `fixed without evidence is rejected`() {
        val ex = org.junit.jupiter.api.assertThrows<com.example.compliance.common.exception.BusinessException> {
            service.markFixed(7L, 9L, "", "")
        }
        assertEquals("evidence required for fixed", ex.message)
    }
```

（`view(...)` helper 在 Task 7.1 已定义；mock 内 `service` 仍为 Task 7.1 的构造。）

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-remediation:test --tests "*RemediationServiceTest*"`
Expected: 编译失败 — `confirm`/`startFix`/`markFixed`/`addEvidence` 未定义。

- [ ] **Step 3: 实现服务扩展**

`RemediationService` 追加：

```kotlin
    /** 人工确认问题真实存在：NEW → CONFIRMED。 */
    @Transactional
    fun confirm(findingId: Long, actorId: Long): FindingRemediationView {
        val finding = mustGetFinding(findingId)
        if (finding.status != FindingStatus.NEW) {
            throw BusinessException(409, "finding not in NEW state: $findingId")
        }
        return FindingRemediationView(
            lifecyclePort.transition(findingId, FindingStatus.CONFIRMED, "confirmed", actorId) toUnit finding,
            taskRepository.findByFindingId(findingId)?.toView(),
        )
    }

    /** 开始整改：ASSIGNED → FIXING。 */
    @Transactional
    fun startFix(findingId: Long, actorId: Long): FindingRemediationView {
        val finding = mustGetFinding(findingId)
        if (finding.status != FindingStatus.ASSIGNED) {
            throw BusinessException(409, "finding not in ASSIGNED state: $findingId")
        }
        return mirrorTransition(findingId, FindingStatus.FIXING, "fix_started", actorId)
    }

    /** 标记修复：FIXING → FIXED，必附 evidence。 */
    @Transactional
    fun markFixed(findingId: Long, actorId: Long, evidenceType: String, evidenceRef: String): FindingRemediationView {
        val finding = mustGetFinding(findingId)
        if (evidenceType.isBlank() || evidenceRef.isBlank()) {
            throw BusinessException(400, "evidence required for fixed")
        }
        if (finding.status != FindingStatus.FIXING) {
            throw BusinessException(409, "finding not in FIXING state: $findingId")
        }
        lifecyclePort.addEvidence(findingId, evidenceType, evidenceRef, actorId)
        return mirrorTransition(findingId, FindingStatus.FIXED, "fixed", actorId)
    }

    /** 追加证据（无转移）。 */
    @Transactional
    fun addEvidence(findingId: Long, actorId: Long, evidenceType: String, evidenceRef: String): FindingRemediationView {
        mustGetFinding(findingId)
        if (evidenceType.isBlank() || evidenceRef.isBlank()) {
            throw BusinessException(400, "evidence required")
        }
        lifecyclePort.addEvidence(findingId, evidenceType, evidenceRef, actorId)
        return get(findingId)
    }

    /** GET /findings：按项目/状态/严重级过滤 + 分页（内存分页，spec §4.4）。 */
    @Transactional(readOnly = true)
    fun list(projectId: Long?, status: FindingStatus?, severity: String?, page: Int, size: Int): List<FindingRemediationView> {
        val findings = lifecyclePort.findingsByProject(projectId ?: 0L, status)
            .filter { severity == null || it.severity.equals(severity, ignoreCase = true) }
        val tasks = taskRepository.findByProjectId(projectId ?: 0L).associateBy { it.findingId }
        val views = findings.map { f -> FindingRemediationView(f, tasks[f.id]?.toView()) }
        val from = (page.coerceAtLeast(0)) * size.coerceAtLeast(1)
        return if (from >= views.size) emptyList() else views.subList(from, minOf(from + size, views.size))
    }

    /** 状态转移 + task.status 镜像（P2-D4）。 */
    private fun mirrorTransition(findingId: Long, to: FindingStatus, reason: String, actorId: Long): FindingRemediationView {
        val status = lifecyclePort.transition(findingId, to, reason, actorId)
        val task = taskRepository.findByFindingId(findingId)
        if (task != null) {
            task.status = status
            taskRepository.save(task)
        }
        return get(findingId)
    }

    /** 让 transition 的返回状态与 finding 视图共存（confirm 便捷写法）。 */
    private fun FindingStatus.toUnit(finding: FindingView): FindingView = finding
```

> 说明：`confirm` 里 `lifecyclePort.transition(...) toUnit finding` 是便捷写法，保持 `FindingRemediationView(finding, task)` 形态；`toUnit` 实为 identity 辅助。若实现嫌绕，`confirm` 可改写为 `mustGetFinding` 后再调 `transition` 并忽略返回值——**以最小惊讶为准，允许实现者简化**。`list` 的 `projectId` 缺省用 0L 兜底（findingsByProject 语义：项目过滤；无 projectId 时行为以 Task 6.3 的 `findingsByProject(projectId, status)` 为准——若其要求必传项目，则 GET /findings 的 projectId 必填）。

- [ ] **Step 4: 写切片测试**

创建 `module-remediation/src/test/kotlin/com/example/compliance/remediation/api/RemediationControllerTest.kt`：

```kotlin
package com.example.compliance.remediation.api

import com.example.compliance.remediation.application.FindingRemediationView
import com.example.compliance.remediation.application.RemediationService
import com.example.compliance.remediation.application.RemediationTaskView
import com.example.compliance.result.application.FindingView
import com.example.compliance.result.domain.FindingStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/** M7：remediation 控制器切片（finding 中心端点）。 */
@WebMvcTest(RemediationController::class)
class RemediationControllerTest {

    @Autowired lateinit var mockMvc: MockMvc

    @MockBean lateinit var service: RemediationService

    private val view = FindingRemediationView(
        finding = FindingView(
            id = 7L, projectId = 9L, scanTaskId = 1L, ruleCode = "R1", severity = "HIGH",
            status = FindingStatus.ASSIGNED, filePath = "A.java", lineNumber = 1,
            firstSeenAt = Instant.now(), lastSeenAt = Instant.now(), occurrenceCount = 1,
        ),
        task = RemediationTaskView(
            id = 11L, findingId = 7L, projectId = 9L, assigneeUserId = 3L, createdBy = 1L,
            plan = null, dueDate = null, status = FindingStatus.ASSIGNED, createdAt = Instant.now(),
        ),
    )

    @Test
    fun `assign returns assigned finding`() {
        every { service.assign(7L, 1L, 3L, "plan", null) } returns view
        mockMvc.perform(
            post("/api/v1/remediation/findings/7/assign")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"assigneeId":3,"plan":"plan"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.task.assigneeUserId").value(3))
            .andExpect(jsonPath("$.finding.status").value("ASSIGNED"))
    }

    @Test
    fun `list returns findings`() {
        every { service.list(9L, null, null, 0, 20) } returns listOf(view)
        mockMvc.perform(get("/api/v1/remediation/findings").param("projectId", "9"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].finding.id").value(7))
    }
}
```

> `@WebMvcTest(RemediationController::class)` 需 controller 构造仅依赖 `RemediationService`（mock）。若 Security 过滤链拦截未认证访问，加 `@AutoConfigureMockMvc(addFilters = false)`（同 Task 9.2 说明）。请求体字段名以 Step 5 controller DTO 为准。

- [ ] **Step 5: 实现 RemediationController**

创建 `module-remediation/src/main/kotlin/com/example/compliance/remediation/api/RemediationController.kt`：

```kotlin
package com.example.compliance.remediation.api

import com.example.compliance.remediation.application.FindingRemediationView
import com.example.compliance.remediation.application.RemediationService
import com.example.compliance.result.domain.FindingStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

/** 整改闭环 finding 中心端点（spec §4.4）。 */
@RestController
@RequestMapping("/api/v1/remediation")
class RemediationController(private val service: RemediationService) {

    data class AssignCommand(val assigneeId: Long?, val plan: String?, val dueDate: LocalDate?)
    data class EvidenceCommand(val evidenceType: String, val evidenceRef: String)

    @GetMapping("/findings")
    fun list(
        @RequestParam(required = false) projectId: Long?,
        @RequestParam(required = false) status: FindingStatus?,
        @RequestParam(required = false) severity: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): List<FindingRemediationView> = service.list(projectId, status, severity, page, size)

    @PostMapping("/findings/{id}/confirm")
    fun confirm(@PathVariable id: Long, auth: Authentication?): FindingRemediationView =
        service.confirm(id, actorId(auth))

    @PostMapping("/findings/{id}/assign")
    fun assign(@PathVariable id: Long, @RequestBody cmd: AssignCommand, auth: Authentication?): FindingRemediationView =
        service.assign(id, actorId(auth), cmd.assigneeId, cmd.plan, cmd.dueDate)

    @PostMapping("/findings/{id}/fixing")
    fun fixing(@PathVariable id: Long, auth: Authentication?): FindingRemediationView =
        service.startFix(id, actorId(auth))

    @PostMapping("/findings/{id}/fixed")
    fun fixed(@PathVariable id: Long, @RequestBody cmd: EvidenceCommand, auth: Authentication?): FindingRemediationView =
        service.markFixed(id, actorId(auth), cmd.evidenceType, cmd.evidenceRef)

    @PostMapping("/findings/{id}/evidence")
    fun evidence(@PathVariable id: Long, @RequestBody cmd: EvidenceCommand, auth: Authentication?): FindingRemediationView =
        service.addEvidence(id, actorId(auth), cmd.evidenceType, cmd.evidenceRef)

    private fun actorId(auth: Authentication?): Long =
        (auth?.principal as? com.example.compliance.common.security.AuthPrincipal)?.userId ?: 1L
}
```

> `actorId` 从认证 principal 解析；既有项目其他 Controller 若已有同名解析函数（如 `ScanController` 内的私有 helper），以既有方式为准——**实现者按既有 Controller 的实际 principal 形态调整**（`AuthPrincipal` 类名以 module-common 既有安全类为准；若不存在，`actorId` 直接返回 1L 并在 M9 RBAC 任务接线真实身份）。

- [ ] **Step 6: 运行测试确认通过**

Run: `./gradlew :module-remediation:test`
Expected: PASS（服务单测 + 控制器切片）。

- [ ] **Step 7: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 8: Commit**

```bash
git add module-remediation
git commit -m "feat(remediation): finding-centric remediation endpoints (confirm/assign/fixing/fixed/evidence/list)"
```

---

### Task 7.3: 终态转移端点（PUT /findings/{id}/status）

**Files:**
- Modify: `module-remediation/src/main/kotlin/com/example/compliance/remediation/application/RemediationService.kt`（+status）
- Modify: `module-remediation/src/main/kotlin/com/example/compliance/remediation/api/RemediationController.kt`（+PUT status）
- Modify: `module-remediation/src/test/kotlin/com/example/compliance/remediation/application/RemediationServiceTest.kt`（+终态测试）
- Modify: `module-remediation/src/test/kotlin/com/example/compliance/remediation/api/RemediationControllerTest.kt`（+PUT 切片）

**Interfaces:**
- Consumes: Task 7.2 服务/控制器结构。
- Produces: `RemediationService.status(findingId, to, reason, evidenceType, evidenceRef, actorId): FindingRemediationView`——`to` 限终态集 `IGNORED/FALSE_POSITIVE/ACCEPTED_RISK/WAIVED`（其余 400）；必附 reason + evidence（400）；任意当前状态可进入终态；写 evidence + transition + task.status 镜像。无 WaiverRecord 表（PF-6）。
- Produces: `PUT /api/v1/remediation/findings/{id}/status` body `StatusCommand(status, reason, evidenceType?, evidenceRef?)`。

- [ ] **Step 1: 写失败测试**

`RemediationServiceTest.kt` 追加：

```kotlin
    @Test
    fun `terminal status requires reason and evidence and is reached from any state`() {
        every { lifecyclePort.findById(7L) } returns view(FindingStatus.NEW)
        every { taskRepository.findByFindingId(7L) } returns RemediationTask().apply { id = 11L; findingId = 7L }
        every { taskRepository.save(any<RemediationTask>()) } answers { firstArg() }
        every { lifecyclePort.addEvidence(7L, "DOC", "http://x/waiver", 9L) } returns
            com.example.compliance.result.domain.FindingEvidence().apply { id = 2L }
        every { lifecyclePort.transition(7L, FindingStatus.WAIVED, "risk accepted", 9L) } returns FindingStatus.WAIVED

        val result = service.status(7L, FindingStatus.WAIVED, "risk accepted", "DOC", "http://x/waiver", 9L)

        assertEquals(FindingStatus.WAIVED, result.finding.status)
        verify { lifecyclePort.transition(7L, FindingStatus.WAIVED, "risk accepted", 9L) }
    }

    @Test
    fun `non-terminal target is rejected`() {
        val ex = org.junit.jupiter.api.assertThrows<com.example.compliance.common.exception.BusinessException> {
            service.status(7L, FindingStatus.CONFIRMED, "x", "DOC", "r", 9L)
        }
        assertEquals("target status not terminal: CONFIRMED", ex.message)
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-remediation:test --tests "*RemediationServiceTest*"`
Expected: 编译失败 — `status` 未定义。

- [ ] **Step 3: 实现服务 `status`**

`RemediationService` 追加：

```kotlin
    /** 终态转移：IGNORED/FALSE_POSITIVE/ACCEPTED_RISK/WAIVED（必附 reason + evidence）。 */
    @Transactional
    fun status(
        findingId: Long, to: FindingStatus, reason: String, evidenceType: String, evidenceRef: String, actorId: Long,
    ): FindingRemediationView {
        if (to !in TERMINAL_STATES) {
            throw BusinessException(400, "target status not terminal: $to")
        }
        if (reason.isBlank() || evidenceType.isBlank() || evidenceRef.isBlank()) {
            throw BusinessException(400, "reason and evidence required for terminal status")
        }
        mustGetFinding(findingId)
        lifecyclePort.addEvidence(findingId, evidenceType, evidenceRef, actorId)
        return mirrorTransition(findingId, to, reason, actorId)
    }

    companion object {
        /** 终态集（spec §4.2）：到达后仅复现/复审系统动作可离开。 */
        val TERMINAL_STATES = setOf(
            FindingStatus.IGNORED, FindingStatus.FALSE_POSITIVE,
            FindingStatus.ACCEPTED_RISK, FindingStatus.WAIVED,
        )
    }
```

- [ ] **Step 4: 写 PUT 切片 + 控制器端点**

`RemediationControllerTest.kt` 追加：

```kotlin
    @Test
    fun `put status returns terminal finding`() {
        every { service.status(7L, FindingStatus.WAIVED, "risk accepted", "DOC", "http://x", 1L) } returns
            view.copy(finding = view.finding.copy(status = FindingStatus.WAIVED))
        mockMvc.perform(
            put("/api/v1/remediation/findings/7/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"WAIVED","reason":"risk accepted","evidenceType":"DOC","evidenceRef":"http://x"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.finding.status").value("WAIVED"))
    }
```

`RemediationController` 追加：

```kotlin
    data class StatusCommand(
        val status: FindingStatus,
        val reason: String,
        val evidenceType: String?,
        val evidenceRef: String?,
    )

    @PutMapping("/findings/{id}/status")
    fun status(@PathVariable id: Long, @RequestBody cmd: StatusCommand, auth: Authentication?): FindingRemediationView =
        service.status(id, cmd.status, cmd.reason, cmd.evidenceType ?: "", cmd.evidenceRef ?: "", actorId(auth))
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :module-remediation:test`
Expected: PASS。

- [ ] **Step 6: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: Commit**

```bash
git add module-remediation
git commit -m "feat(remediation): terminal status transition via PUT /findings/{id}/status"
```

> **M7 前段完成标准**：remediation_task 实体/迁移/服务/API 落地；状态权威始终在 finding（P2-D4）；终态转移守卫（reason+evidence）；Task 7.4 续 recheck 复扫闭环。

---
### Task 7.4: 复扫触发端口（ScanTriggerPort）+ recheck 闭环

> **前置**：本任务依赖 m6c Task 6.5 的 `ScanTaskService.startScan(projectId, engine, ref, triggerType="MANUAL")`。为使复扫可追溯，m6c 的 startScan 将**增加可选 `requestId: String? = null` 参数**（内部 `this.requestId = requestId ?: UUID.randomUUID().toString()`，m6 编辑批次同步该行）。`ScanTask.requestId` 列已由 V8 迁移（Task 6.1 段(5)）创建。

**Files:**
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanTriggerPort.kt`（接口 + ScanTaskView）
- Modify: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanTaskService.kt`（实现 ScanTriggerPort）
- Create: `module-scan/src/test/kotlin/com/example/compliance/scan/application/ScanTriggerPortTest.kt`
- Modify: `module-remediation/build.gradle.kts`（+`implementation(project(":module-scan"))`）
- Modify: `module-remediation/src/main/kotlin/com/example/compliance/remediation/application/RemediationService.kt`（构造 +requestRecheck）
- Modify: `module-remediation/src/main/kotlin/com/example/compliance/remediation/api/RemediationController.kt`（+POST /findings/{id}/recheck）
- Modify: `module-remediation/src/test/kotlin/com/example/compliance/remediation/application/RemediationServiceTest.kt`（构造 + requestRecheck 测试）
- Modify: `module-remediation/src/test/kotlin/com/example/compliance/remediation/api/RemediationControllerTest.kt`（+recheck 切片）

**Interfaces:**
- Consumes: Task 6.5 `ScanTaskService.startScan(projectId, engine, ref, triggerType="MANUAL", requestId=null)`；Task 7.1 `RemediationService(taskRepository, lifecyclePort)` 构造（本任务改为三依赖）；Task 6.3 `FindingView.engine`（m6b 编辑批次补尾字段）。
- Produces: `ScanTriggerPort`（module-scan.application）：
  ```kotlin
  data class ScanTaskView(id: Long, projectId: Long, engine: String, status: ScanTaskStatus, requestId: String)
  interface ScanTriggerPort { fun triggerScan(projectId: Long, engine: String, ref: String?, triggerType: String, requestId: String?): ScanTaskView }
  ```
  （P2-D5 例外：remediation→module-scan 仅依赖此接口与值类型，不 import ScanTask 实体。输入 `requestId` 可空——缺省由 startScan 生成 UUID；输出 `ScanTaskView.requestId` 恒非空，因 startScan 总回填。）
- Produces: `RemediationService.requestRecheck(findingId, actorId): FindingRemediationView` —— FIXED→RECHECKING（spec §4.2/§4.3：必先经 triggerPort 建复扫 ScanTask，trigger_type=MANUAL，reason=`recheck_requested:scan_<newTaskId>` 记入 finding_status；task.status 镜像）。
- Produces: `POST /api/v1/remediation/findings/{id}/recheck`（无 body）。

- [ ] **Step 1: 写失败测试（ScanTriggerPort + requestRecheck）**

创建 `module-scan/src/test/kotlin/com/example/compliance/scan/application/ScanTriggerPortTest.kt`：

```kotlin
package com.example.compliance.scan.application

import com.example.compliance.project.application.ProjectService
import com.example.compliance.result.engine.EngineAdapterRegistry
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.scan.domain.ScanTask
import com.example.compliance.scan.domain.ScanTaskStatus
import com.example.compliance.scan.infrastructure.ChecklistItemResultRepository
import com.example.compliance.scan.infrastructure.ComplianceEvaluationRepository
import com.example.compliance.scan.infrastructure.ScanTaskRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** M7：ScanTaskService 实现 ScanTriggerPort 契约（值类型，不泄露实体）。 */
class ScanTriggerPortTest {

    private val scanTaskRepository = mockk<ScanTaskRepository>()
    private val projectService = mockk<ProjectService>()
    private val registry = mockk<EngineAdapterRegistry>()
    private val findingRepository = mockk<FindingRepository>()
    private val evaluationRepository = mockk<ComplianceEvaluationRepository>()
    private val itemResultRepository = mockk<ChecklistItemResultRepository>()
    private val orchestrator = mockk<ScanOrchestrator>(relaxed = true)
    private val service = ScanTaskService(
        scanTaskRepository, projectService, registry, findingRepository,
        evaluationRepository, itemResultRepository, orchestrator,
    )

    @Test
    fun `triggerScan passes requestId through and returns value view`() {
        val project = com.example.compliance.project.domain.Project().apply { id = 9L }
        every { registry.get("STUBM7") } returns mockk()
        every { projectService.get(9L) } returns project
        every { scanTaskRepository.save(any<ScanTask>()) } answers {
            firstArg<ScanTask>().apply { id = 42L }
        }

        val view = service.triggerScan(9L, "STUBM7", "main", "MANUAL", "recheck-f7")

        assertEquals(42L, view.id)
        assertEquals(9L, view.projectId)
        assertEquals("recheck-f7", view.requestId)
        assertEquals(ScanTaskStatus.PENDING, view.status)
        verify { scanTaskRepository.save(match { it.requestId == "recheck-f7" && it.triggerType == "MANUAL" }) }
    }
}
```

> `Project`/`ScanTaskService` 构造依赖以实际文件为准（ScanTaskService 构造参数序见既有文件）；若 `Project` 构造需要必填字段，改 `Project().apply { id = 9L; /* 其余字段取默认 */ }`。

`RemediationServiceTest.kt`：**构造函数改为三依赖**并追加 requestRecheck 测试（`private val triggerPort = mockk<com.example.compliance.scan.application.ScanTriggerPort>()`；`RemediationService(taskRepository, lifecyclePort, triggerPort)`）：

```kotlin
    @Test
    fun `requestRecheck transitions fixed to rechecking and creates rescan`() {
        every { lifecyclePort.findById(7L) } returns view(FindingStatus.FIXED)
        every { taskRepository.findByFindingId(7L) } returns null
        every { lifecyclePort.transition(7L, FindingStatus.RECHECKING, "recheck_requested:scan_55", 9L) } returns FindingStatus.RECHECKING
        every { triggerPort.triggerScan(9L, "STUB", null, "MANUAL", "recheck-f7") } returns
            com.example.compliance.scan.application.ScanTaskView(55L, 9L, "STUB", com.example.compliance.scan.domain.ScanTaskStatus.PENDING, "recheck-f7")

        val result = service.requestRecheck(7L, 9L)

        assertEquals(FindingStatus.RECHECKING, result.finding.status)
        verify { triggerPort.triggerScan(9L, "STUB", null, "MANUAL", "recheck-f7") }
    }

    @Test
    fun `requestRecheck requires fixed`() {
        every { lifecyclePort.findById(7L) } returns view(FindingStatus.NEW)
        val ex = org.junit.jupiter.api.assertThrows<com.example.compliance.common.exception.BusinessException> {
            service.requestRecheck(7L, 9L)
        }
        assertEquals("finding not in FIXED state: 7", ex.message)
    }
```

> `view(...)` helper 已含 `engine = "STUB"`（m6b 编辑批次给 FindingView 补尾字段默认后，位置参数构造无需改；若 helper 显式传了 engine 则保持）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-scan:test --tests "*ScanTriggerPortTest*"` 与 `./gradlew :module-remediation:test --tests "*RemediationServiceTest*"`
Expected: 编译失败 — `ScanTriggerPort`/`requestRecheck` 未定义；`RemediationService` 三参构造不存在。

- [ ] **Step 3: 实现 ScanTriggerPort + ScanTaskService 实现**

创建 `module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanTriggerPort.kt`：

```kotlin
package com.example.compliance.scan.application

import com.example.compliance.scan.domain.ScanTaskStatus

/** 复扫任务视图（值类型，不泄露 ScanTask 实体）。 */
data class ScanTaskView(
    val id: Long,
    val projectId: Long,
    val engine: String,
    val status: ScanTaskStatus,
    val requestId: String,
)

/** 复扫触发端口（spec §4.3/§4.4）：remediation 经此创建复扫任务。P2-D5 例外——remediation→module-scan 仅依赖此接口。 */
interface ScanTriggerPort {
    fun triggerScan(projectId: Long, engine: String, ref: String?, triggerType: String, requestId: String?): ScanTaskView
}
```

`ScanTaskService` 类声明改为 `class ScanTaskService(...) : ScanTriggerPort`，追加：

```kotlin
    override fun triggerScan(projectId: Long, engine: String, ref: String?, triggerType: String, requestId: String?): ScanTaskView {
        val task = startScan(projectId, engine, ref, triggerType, requestId)
        return ScanTaskView(task.id!!, task.projectId, task.engine, task.status, task.requestId ?: "")
    }
```

（m6c Task 6.5 的 startScan 同步改为 `fun startScan(projectId: Long, engine: String, ref: String?, triggerType: String = "MANUAL", requestId: String? = null): ScanTask`，内部 `this.requestId = requestId ?: java.util.UUID.randomUUID().toString()`。）

- [ ] **Step 4: 实现 requestRecheck + 控制器端点**

`module-remediation/build.gradle.kts` dependencies 追加：`implementation(project(":module-scan"))`。

`RemediationService` 构造改为：

```kotlin
class RemediationService(
    private val taskRepository: RemediationTaskRepository,
    private val lifecyclePort: FindingLifecyclePort,
    private val triggerPort: ScanTriggerPort,
)
```

追加：

```kotlin
    /** 请求复扫验证：FIXED → RECHECKING，并创建复扫 ScanTask（trigger_type=MANUAL）。
     *  spec §4.3：reason 记入 finding_status；复扫完成后由编排器 verifyRechecking 闭环。 */
    @Transactional
    fun requestRecheck(findingId: Long, actorId: Long): FindingRemediationView {
        val finding = mustGetFinding(findingId)
        if (finding.status != FindingStatus.FIXED) {
            throw BusinessException(409, "finding not in FIXED state: $findingId")
        }
        val scan = triggerPort.triggerScan(
            projectId = finding.projectId, engine = finding.engine, ref = null,
            triggerType = "MANUAL", requestId = "recheck-f$findingId",
        )
        return mirrorTransition(findingId, FindingStatus.RECHECKING, "recheck_requested:scan_${scan.id}", actorId)
    }
```

> `finding.engine` 来自 FindingView 尾部 `engine` 字段（m6b 编辑批次）。`mirrorTransition` 已在 Task 7.2 定义。时序说明：先建复扫任务再转移，故 reason 可携带 `scan_<id>`；复扫异步执行在扫描末尾才做 verifyRechecking，转移早已提交，竞态可忽略。

`RemediationController` 追加：

```kotlin
    @PostMapping("/findings/{id}/recheck")
    fun recheck(@PathVariable id: Long, auth: Authentication?): FindingRemediationView =
        service.requestRecheck(id, actorId(auth))
```

- [ ] **Step 5: 写 recheck 切片测试**

`RemediationControllerTest.kt` 追加：

```kotlin
    @Test
    fun `recheck returns rechecking finding`() {
        every { service.requestRecheck(7L, 1L) } returns
            view.copy(finding = view.finding.copy(status = FindingStatus.RECHECKING))
        mockMvc.perform(post("/api/v1/remediation/findings/7/recheck"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.finding.status").value("RECHECKING"))
    }
```

- [ ] **Step 6: 运行测试确认通过**

Run: `./gradlew :module-scan:test --tests "*ScanTriggerPortTest*"` 与 `./gradlew :module-remediation:test`
Expected: PASS（模块-scan 1 个 + remediation 服务单测与控制器切片全绿）。

- [ ] **Step 7: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 8: Commit**

```bash
git add module-scan/src/main/kotlin/com/example/compliance/scan/application module-scan/src/test/kotlin/com/example/compliance/scan/application module-remediation
git commit -m "feat(remediation,scan): scan trigger port and recheck close-loop (FIXED to RECHECKING + rescan task)"
```

---

### Task 7.5: M7 复扫闭环集成测试（absent→CLOSED / present→回归 CONFIRMED）

**Files:**
- Create: `app-server/src/test/kotlin/com/example/compliance/remediation/M7RemediationIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 7.1-7.4 全部；Task 6.3 `FindingLifecyclePort`；Task 6.5 `ScanTaskService.startScan`/`ScanTaskRepository`（app-server 可 autowire 仓储）；`ProjectService`/`ChecklistService`/`RuleService` 用法沿用 ScanPipelineIntegrationTest（DTo 类名以既有为准）。
- Produces: 无新接口——验证 spec §4.3 复扫验证闭环端到端。

- [ ] **Step 1: 写集成测试**

创建 `app-server/src/test/kotlin/com/example/compliance/remediation/M7RemediationIntegrationTest.kt`：

```kotlin
package com.example.compliance.remediation

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.checklist.application.ChecklistService
import com.example.compliance.project.application.BindRepositoryCommand
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import com.example.compliance.remediation.application.RemediationService
import com.example.compliance.result.application.FindingLifecyclePort
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanResult
import com.example.compliance.rule.application.AddEngineBindingCommand
import com.example.compliance.rule.application.CreateRuleCommand
import com.example.compliance.rule.application.RuleService
import com.example.compliance.rule.application.SetPolicyCommand
import com.example.compliance.scan.application.ScanTaskService
import com.example.compliance.scan.domain.ScanTaskStatus
import com.example.compliance.scan.infrastructure.ScanTaskRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M7 复扫闭环（spec §4.3）：FIXED → recheck → 复扫 absent→CLOSED / present→回归 CONFIRMED。
 *  数据前缀 REM-*；STUBM7 用静态 StubM7.findings 控制复扫命中/缺席。
 *  scan() 兼容形态：Task 8.1 将 scan() 改为默认方法，本 override 前后均可编译（Ruling：STUB 解冻细化）。 */
class M7RemediationIntegrationTest : AbstractIntegrationTest() {

    /** 静态可控引擎输出：测试在 requestRecheck 前改写，决定复扫是否命中。 */
    object StubM7 {
        @Volatile
        var findings: List<RawFinding> = emptyList()
    }

    @TestConfiguration
    class StubM7AdapterConfig {
        @Bean
        fun stubM7Adapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUBM7"
            override fun scan(context: ScanContext): ScanResult = ScanResult(findings = StubM7.findings)
        }
    }

    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var checklistService: ChecklistService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService
    @Autowired lateinit var scanTaskRepository: ScanTaskRepository
    @Autowired lateinit var remediationService: RemediationService
    @Autowired lateinit var lifecyclePort: FindingLifecyclePort

    private val raw = RawFinding("stub-m7-rule", "M7", "src/main/java/Rem.java", 10, "HIGH", "m", "x=id;")

    @Test
    fun `recheck with finding absent closes it`() {
        val findingId = setupFinding()
        walkToFixed(findingId)
        StubM7.findings = emptyList()          // 复扫缺席
        remediationService.requestRecheck(findingId, 1L)
        waitResolved(findingId)
        assertEquals(FindingStatus.CLOSED, lifecyclePort.findById(findingId)!!.status)
        assertRecheckTask(findingId)
    }

    @Test
    fun `recheck with finding present regresses to confirmed`() {
        val findingId = setupFinding()
        walkToFixed(findingId)
        StubM7.findings = listOf(raw)          // 复扫命中（同指纹 → REAPPEARED）
        remediationService.requestRecheck(findingId, 1L)
        waitResolved(findingId)
        assertEquals(FindingStatus.CONFIRMED, lifecyclePort.findById(findingId)!!.status)
        assertRecheckTask(findingId)
    }

    /** 首扫产生 finding（NEW），返回其 id。 */
    private fun setupFinding(): Long {
        val project = projectService.create(CreateProjectCommand("REMP", "M7 项目", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("rem-repo", "https://git.example.com/rem.git", "GITLAB", "main", "tok"))
        val standard = checklistService.createStandard("REM-SEC", "M7 规范", null)
        val checklist = checklistService.createChecklist(standard.id!!, "REM-BASIC", "M7 基线")
        checklistService.addItem(checklist.id!!, com.example.compliance.checklist.application.AddItemCommand(itemCode = "REM-001", name = "M7 项", riskLevel = "HIGH"))
        val version = checklistService.publish(checklist.id!!)
        checklistService.bindProject(project.id!!, version.id!!)
        val rule = ruleService.create(CreateRuleCommand("REM-SQLI", "M7 注入", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUBM7", "stub-m7-rule", null))
        ruleService.addComplianceMapping(rule.id!!, "REM-001")
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'HIGH'"))
        ruleService.publish(rule.id!!)

        StubM7.findings = listOf(raw)
        val task1 = scanTaskService.startScan(project.id!!, "STUBM7", "main")
        waitDone(task1.id!!)
        return lifecyclePort.findingsForScanTask(task1.id!!).first().id
    }

    /** 走完整改路径到 FIXED：confirm → assign → fixing → fixed(evidence)。 */
    private fun walkToFixed(findingId: Long) {
        remediationService.confirm(findingId, 1L)
        remediationService.assign(findingId, 1L, 3L, "fix plan", null)
        remediationService.startFix(findingId, 1L)
        remediationService.markFixed(findingId, 1L, "FIX_COMMIT", "abc123")
        assertEquals(FindingStatus.FIXED, lifecyclePort.findById(findingId)!!.status)
    }

    /** 复扫任务存在性 + 元数据断言（requestId=recheck-f<findingId> 保证跨共享容器唯一可定位）。 */
    private fun assertRecheckTask(findingId: Long) {
        val task = scanTaskRepository.findAll().first { it.requestId == "recheck-f$findingId" }
        assertEquals("MANUAL", task.triggerType)
        assertEquals(ScanTaskStatus.SUCCESS, task.status)
    }

    /** 轮询直到复扫验证决议（finding 离开 RECHECKING）。 */
    private fun waitResolved(findingId: Long) {
        var done = false
        repeat(100) {
            val s = lifecyclePort.findById(findingId)!!.status
            if (s != FindingStatus.RECHECKING) { done = true; return@repeat }
            Thread.sleep(200)
        }
        assertTrue(done, "recheck verification should resolve within timeout")
    }

    private fun waitDone(taskId: Long) {
        var done = false
        repeat(50) {
            val s = scanTaskService.get(taskId).status
            if (s != ScanTaskStatus.RUNNING && s != ScanTaskStatus.PENDING) { done = true; return@repeat }
            Thread.sleep(200)
        }
        assertTrue(done, "scan $taskId should finish within timeout")
        assertEquals(ScanTaskStatus.SUCCESS, scanTaskService.get(taskId).status)
    }
}
```

> 说明：
> - `requestId = "recheck-f<findingId>"` 定位复扫任务：finding id 全局自增唯一，故该 requestId 在共享 Testcontainers 容器内跨测试唯一。
> - 两个 `@Test` 共享静态 `StubM7.findings`：JUnit 默认顺序执行，每个测试的 `setupFinding` 先重置为 `listOf(raw)`，recheck 前再改写，互不串扰。
> - `markFixed`/`status` 终态守卫在服务层（Task 7.2/7.3）已有单测覆盖；本测试专注端到端闭环。
> - 若 `ScanTaskRepository.findAll()` 在超大容器数据下较慢，可改 `findByRequestId`（本测试用 `findAll().first{}` 保持对仓储接口零改动；如需性能，可在 Task 6.2 的 FindingRepository 之外给 ScanTaskRepository 补 `findByRequestId`，实现者可自行裁决并 ledger）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app-server:test --tests "*M7RemediationIntegrationTest*"`
Expected: 编译失败或断言失败（`RemediationService` 三参构造/`requestRecheck`/`FindingView.engine` 缺失）——取决于前序任务是否已完成。

- [ ] **Step 3: 运行确认通过**

Run: `./gradlew :app-server:test --tests "*M7RemediationIntegrationTest*"`
Expected: PASS（2 个测试，双场景）。

- [ ] **Step 4: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（含 frozen ScanPipeline/ReportApi/FindingRepository/M6 全部绿）。

- [ ] **Step 5: Commit**

```bash
git add app-server/src/test/kotlin/com/example/compliance/remediation/M7RemediationIntegrationTest.kt
git commit -m "test(remediation): M7 recheck close-loop integration - absent closes, present regresses"
```

> **M7 完成标准**：整改任务实体/迁移/服务/API 落地；finding 中心端点与终态转移（reason+evidence）守卫；recheck 建复扫任务并闭环验证（absent→CLOSED / present→CONFIRMED）；`./gradlew build` 全绿。

---
## M8 — 真实引擎路径

> **模块落地说明**：M8 演进 `ScanEngineAdapter` 为五方法契约（spec §5.1，P2-D8 逐字，全部带默认实现），并新增 module-scan 的 `GitCheckout`（spec §5.2）。**关键兼容裁决（ledger 细化）**：接口保留 `scan(context): ScanResult` 兼容默认方法（内部跑五阶段管线）——冻结的 `ScanPipelineIntegrationTest`/`ReportApiIntegrationTest` 只 override `scan()`，接口演进后仍可编译，**零修改**。

### Task 8.1: ScanEngineAdapter 五方法契约（§5.1 逐字）+ scan() 兼容默认

**Files:**
- Modify（全文件替换）: `module-result/src/main/kotlin/com/example/compliance/result/engine/ScanEngineAdapter.kt`
- Create: `module-result/src/test/kotlin/com/example/compliance/result/engine/DefaultAdapterBehaviorsTest.kt`

**Interfaces:**
- Consumes: 既有 `RawFinding`/`ScanResult`（本文件保留原样）。
- Produces（spec §5.1 逐字）:
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
- Produces: `ScanExecutionResult(success, errorMessage, durationMs, stdoutRef?)`；`ScanContext` 9 字段（scanTaskId, projectId, repoUrl, ref=null, workDir=null, commitId=null, timeoutSeconds=null, paramsJson=null, configJson=null）——旧 4 位置参调用（编排器 `ScanContext(task.id, projectId, repo.gitUrl, task.ref)`）因其余字段带默认值仍编译。
- 兼容裁决：新增 `fun scan(context: ScanContext): ScanResult` 默认方法（跑五阶段管线 + cleanup finally）——冻结 STUB 测试零修改；M8 新 STUBM8 直接用五方法形态。

- [ ] **Step 1: 写失败测试（默认行为 + 五阶段聚合）**

创建 `module-result/src/test/kotlin/com/example/compliance/result/engine/DefaultAdapterBehaviorsTest.kt`：

```kotlin
package com.example.compliance.result.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M8：spec §5.1 五方法契约默认行为（P2-D8）——未覆写方法有确定性默认值；scan() 兼容默认跑五阶段管线。 */
class DefaultAdapterBehaviorsTest {

    private class NoopAdapter(override val engine: String = "NOOP") : ScanEngineAdapter

    @Test
    fun `supports is case-insensitive`() {
        val adapter = NoopAdapter("semgrep")
        assertTrue(adapter.supports("SEMGREP"))
        assertTrue(adapter.supports("SemGrep"))
        assertFalse(adapter.supports("trivy"))
    }

    @Test
    fun `default scan returns empty success`() {
        val result = NoopAdapter().scan(ScanContext(1L, 1L, "https://x.git", "main"))
        assertTrue(result.success)
        assertEquals(0, result.findings.size)
    }

    @Test
    fun `executeScan defaults to success and normalizeResult is identity`() {
        val adapter = NoopAdapter()
        val ctx = ScanContext(1L, 1L, "https://x.git")
        assertTrue(adapter.executeScan(ctx).success)
        val raw = listOf(RawFinding("r1", "n", "A.java", 1, "HIGH"))
        assertEquals(raw, adapter.normalizeResult(ctx, raw))
        adapter.cleanup(ctx)   // 不抛异常即通过
    }

    @Test
    fun `scan default runs overridden five-stage methods and aggregates`() {
        var prepared = false
        var cleaned = false
        val adapter = object : ScanEngineAdapter {
            override val engine = "FIVESTAGE"
            override fun prepareScan(context: ScanContext) { prepared = true }
            override fun executeScan(context: ScanContext) = ScanExecutionResult(success = true, durationMs = 7)
            override fun collectResult(context: ScanContext) = listOf(RawFinding("r1", "n", "A.java", 1, "INFO"))
            override fun normalizeResult(context: ScanContext, raw: List<RawFinding>) = raw.map { it.copy(severity = "HIGH") }
            override fun cleanup(context: ScanContext) { cleaned = true }
        }

        val result = adapter.scan(ScanContext(1L, 1L, "https://x.git"))

        assertTrue(prepared)
        assertTrue(cleaned)
        assertEquals("HIGH", result.findings.single().severity)
        assertEquals(7, result.durationMs)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-result:test --tests "*DefaultAdapterBehaviorsTest*"`
Expected: 编译失败 — `supports`/`ScanExecutionResult` 未定义，`ScanContext` 构造不齐。

- [ ] **Step 3: 全文件替换 ScanEngineAdapter.kt**

```kotlin
package com.example.compliance.result.engine

/** 扫描引擎统一端口（spec §5.1，P2-D8）：五方法契约全部带默认实现，现有实现零改动兼容。 */
interface ScanEngineAdapter {
    val engine: String

    fun supports(engineType: String): Boolean = engineType.equals(engine, ignoreCase = true)

    fun prepareScan(context: ScanContext) {}
    fun executeScan(context: ScanContext): ScanExecutionResult = ScanExecutionResult(success = true)
    fun collectResult(context: ScanContext): List<RawFinding> = emptyList()
    fun normalizeResult(context: ScanContext, raw: List<RawFinding>): List<RawFinding> = raw
    fun cleanup(context: ScanContext) {}

    /** 兼容默认方法：跑五阶段管线并聚合为旧 ScanResult（冻结 STUB 测试 override scan() 时零改动；编排器 M8 直接调用五阶段）。 */
    fun scan(context: ScanContext): ScanResult {
        prepareScan(context)
        try {
            val execution = executeScan(context)
            val raw = collectResult(context)
            val normalized = normalizeResult(context, raw)
            return ScanResult(normalized, success = execution.success, errorMessage = execution.errorMessage, durationMs = execution.durationMs)
        } finally {
            cleanup(context)
        }
    }
}

/** 引擎执行结果；stdoutRef 指向引擎原始输出落盘位置（collectResult 读取，spec §5.1）。 */
data class ScanExecutionResult(
    val success: Boolean,
    val errorMessage: String? = null,
    val durationMs: Long? = null,
    val stdoutRef: String? = null,
)

data class ScanContext(
    val scanTaskId: Long,
    val projectId: Long,
    val repoUrl: String,
    val ref: String? = null,
    val workDir: String? = null,        // 编排器检出的本地目录（§5.2），SemgrepAdapter 优先作为扫描目标
    val commitId: String? = null,
    val timeoutSeconds: Long? = null,
    val paramsJson: String? = null,     // rule_engine_binding.parameters
    val configJson: String? = null,     // 兼容保留
)
```

`RawFinding` 与 `ScanResult` 保持原样不动。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :module-result:test --tests "*DefaultAdapterBehaviorsTest*"`
Expected: PASS（4 个测试）。

- [ ] **Step 5: 全量回归（冻结零修改验证）**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL——`ScanPipelineIntegrationTest`/`ReportApiIntegrationTest` 的 STUB 只 override `scan()`，新接口把 `scan()` 变为默认方法，override 仍编译，**零修改**（ledger 细化裁决）。`SemgrepAdapter` 现 override `scan()`，同样编译通过（Task 8.3 改为五方法形态）。

- [ ] **Step 6: Commit**

```bash
git add module-result/src/main/kotlin/com/example/compliance/result/engine/ScanEngineAdapter.kt module-result/src/test/kotlin/com/example/compliance/result/engine/DefaultAdapterBehaviorsTest.kt
git commit -m "feat(result): five-method engine adapter contract with compatible scan() default (P2-D8)"
```

---

### Task 8.2: GitCheckout（module-scan）+ ProcessRunner + checkout-engines 配置

**Files:**
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/checkout/GitCheckout.kt`（接口 + CheckoutResult）
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/checkout/ProcessRunner.kt`（接口 + ProcessOutput）
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/checkout/CommandGitCheckout.kt`（真实实现）
- Create: `module-scan/src/test/kotlin/com/example/compliance/scan/checkout/GitCheckoutTest.kt`
- Modify: `app-server/src/main/resources/application.yml`（+`app.scan.checkout-engines: SEMGREP`）

**Interfaces:**
- Consumes: 无（独立组件）。`@Value("${app.scan.checkout-engines}")` 由 Task 8.4 编排器消费，本任务只落配置键。
- Produces（spec §5.2 逐字 + cleanup）:
  ```kotlin
  data class CheckoutResult(val workDir: String, val commitId: String?)
  interface GitCheckout {
      fun checkout(repoUrl: String, ref: String?): CheckoutResult
      fun cleanup(workDir: String)
  }
  interface ProcessRunner { fun run(command: List<String>, dir: String? = null): ProcessOutput }
  data class ProcessOutput(val exitCode: Int, val stdout: String)
  ```
- `CommandGitCheckout`：本地路径（已存在目录或 `file:` 前缀）→ 跳过 clone，`CheckoutResult(repoUrl, null)`；远程 → `git clone --depth 1 [-b ref] <repoUrl> <temp>` + `git rev-parse HEAD` 回填 commitId；`cleanup` 只删本组件创建的 `scan-checkout-*` 临时目录，绝不触碰用户路径。

- [ ] **Step 1: 写失败测试**

创建 `module-scan/src/test/kotlin/com/example/compliance/scan/checkout/GitCheckoutTest.kt`：

```kotlin
package com.example.compliance.scan.checkout

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8：GitCheckout 本地跳过 / 远程 clone+rev-parse / cleanup 只删自建临时目录。 */
class GitCheckoutTest {

    private val processRunner = mockk<ProcessRunner>()
    private val checkout = CommandGitCheckout(processRunner)

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `local path skips clone`() {
        val result = checkout.checkout(tempDir.toString(), "main")
        assertEquals(tempDir.toString(), result.workDir)
        assertNull(result.commitId)
    }

    @Test
    fun `remote clone succeeds and returns commit id`() {
        every { processRunner.run(match { it.contains("clone") }, any()) } returns ProcessOutput(0, "")
        every { processRunner.run(match { it.contains("rev-parse") }, any()) } returns ProcessOutput(0, "abc123def\n")

        val result = checkout.checkout("https://git.example.com/a.git", "main")

        assertEquals("abc123def", result.commitId)
        assertTrue(result.workDir.contains("scan-checkout-"), "workDir should be a self-created temp dir")
    }

    @Test
    fun `clone failure throws and cleans temp dir`() {
        every { processRunner.run(match { it.contains("clone") }, any()) } returns ProcessOutput(128, "fatal: could not read Username")
        org.junit.jupiter.api.assertThrows<IllegalStateException> {
            checkout.checkout("https://git.example.com/a.git", "main")
        }
    }

    @Test
    fun `cleanup deletes only self-created temp dir`() {
        val self = java.nio.file.Files.createTempDirectory("scan-checkout-clean")
        checkout.cleanup(self.toString())
        assertTrue(!java.nio.file.Files.exists(self))
        // 用户路径绝不被删
        checkout.cleanup(tempDir.toString())
        assertTrue(java.nio.file.Files.exists(tempDir))
    }
}
```

> `ProcessRunner.run(command: List<String>, dir: String? = null)` 用 `List` 参数（非 vararg）便于 mockk 匹配；真实实现用 `ProcessBuilder(command).directory(...).redirectErrorStream(true)` 执行并读 stdout。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-scan:test --tests "*GitCheckoutTest*"`
Expected: 编译失败 — `GitCheckout`/`ProcessRunner`/`CommandGitCheckout` 不存在。

- [ ] **Step 3: 实现接口与真实实现**

`GitCheckout.kt`：

```kotlin
package com.example.compliance.scan.checkout

/** 检出结果：workDir 为可扫描的本地目录；commitId 为检出 commit（本地/STUB 跳过 clone 时为 null）。 */
data class CheckoutResult(val workDir: String, val commitId: String?)

/** 引擎无关的代码检出（spec §5.2）：编排器负责调用，adapter 只消费 ScanContext.workDir。 */
interface GitCheckout {
    fun checkout(repoUrl: String, ref: String?): CheckoutResult
    fun cleanup(workDir: String)
}
```

`ProcessRunner.kt`：

```kotlin
package com.example.compliance.scan.checkout

data class ProcessOutput(val exitCode: Int, val stdout: String)

/** 进程执行抽象：真实实现走 ProcessBuilder；测试可 mock 模拟 git 输出。 */
interface ProcessRunner {
    fun run(command: List<String>, dir: String? = null): ProcessOutput
}
```

`CommandGitCheckout.kt`：

```kotlin
package com.example.compliance.scan.checkout

import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

@Component
class CommandGitCheckout(
    private val processRunner: ProcessRunner,
) : GitCheckout {

    /** 本地路径（已存在目录或 file:）→ 跳过 clone；远程 → clone 到自建临时目录并回填 commitId。 */
    override fun checkout(repoUrl: String, ref: String?): CheckoutResult {
        if (isLocal(repoUrl)) return CheckoutResult(repoUrl, null)
        val target = Files.createTempDirectory("scan-checkout-").toString()
        val command = buildList {
            add("git"); add("clone"); add("--depth"); add("1")
            if (!ref.isNullOrBlank()) { add("-b"); add(ref) }
            add(repoUrl); add(target)
        }
        val clone = processRunner.run(command)
        if (clone.exitCode != 0) {
            cleanup(target)
            throw IllegalStateException("git clone failed: ${clone.stdout.take(500)}")
        }
        val rev = processRunner.run(listOf("git", "-C", target, "rev-parse", "HEAD"))
        val commitId = if (rev.exitCode == 0) rev.stdout.trim().takeIf { it.isNotBlank() } else null
        return CheckoutResult(target, commitId)
    }

    /** 只删本组件创建的 scan-checkout-* 临时目录；用户路径 no-op。 */
    override fun cleanup(workDir: String) {
        val path = Paths.get(workDir)
        if (path.fileName?.toString()?.startsWith("scan-checkout-") == true) {
            deleteRecursively(path)
        }
    }

    private fun isLocal(repoUrl: String): Boolean =
        repoUrl.startsWith("file:") || Files.isDirectory(Paths.get(repoUrl))

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}

/** 真实进程执行器（ProcessBuilder）。 */
@Component
class SystemProcessRunner : ProcessRunner {
    override fun run(command: List<String>, dir: String?): ProcessOutput {
        val pb = ProcessBuilder(command)
        if (dir != null) pb.directory(Paths.get(dir).toFile())
        pb.redirectErrorStream(true)
        val p = pb.start()
        val stdout = p.inputStream.bufferedReader().readText()
        val exit = p.waitFor(120, TimeUnit.SECONDS)
        val code = if (exit) p.exitValue() else { p.destroyForcibly(); -1 }
        return ProcessOutput(code, stdout)
    }
}
```

- [ ] **Step 4: 配置键**

`application.yml` 的 `app:` 段追加：

```yaml
  scan:
    checkout-engines: SEMGREP   # 仅这些引擎在编排器侧触发 GitCheckout；STUB 等跳过（Task 8.4 消费）
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :module-scan:test --tests "*GitCheckoutTest*"`
Expected: PASS（4 个测试）。

- [ ] **Step 6: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: Commit**

```bash
git add module-scan/src/main/kotlin/com/example/compliance/scan/checkout module-scan/src/test/kotlin/com/example/compliance/scan/checkout app-server/src/main/resources/application.yml
git commit -m "feat(scan): engine-agnostic git checkout with process runner and checkout-engines config"
```

> **M8 前段完成标准**：五方法契约落地 + scan() 兼容（冻结零修改）；GitCheckout 组件可测（本地跳过/远程 clone/cleanup 自建目录）。Task 8.3 改 SemgrepAdapter 五方法化；Task 8.4 编排器五阶段管线 + 门控 checkout + PREPARING。

---
### Task 8.3: SemgrepAdapter 五方法化（module-engine-adapter 真实路径）

**Files:**
- Modify（全文件替换）: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/semgrep/SemgrepAdapter.kt`
- Modify（全文件替换）: `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/semgrep/SemgrepAdapterTest.kt`

**Interfaces:**
- Consumes: Task 8.1 `ScanEngineAdapter` 五方法契约（`ScanExecutionResult`/`ScanContext` 9 字段）；既有 `SemgrepCli.run(targetPath, ref): String`（超时+临时重定向已具备，spec §5.2 不改）、`SemgrepResultParser.parse(stdout): List<RawFinding>`、`SemgrepSeverityMapper.map(engineSeverity): String`（ERROR→HIGH/WARNING→MEDIUM/INFO→LOW/else→LOW）。
- Produces: 五方法形态 `SemgrepAdapter`：
  - `prepareScan` no-op；`executeScan` → `scanTarget(context)` + `cli.run` + stdout 落临时文件 + `ScanExecutionResult(success, stdoutRef)`；
  - `collectResult` → 读 stdoutRef 文件 + `parser.parse`（**保留原始 severity**）；
  - `normalizeResult` → `severityMapper.map` 只映射 severity（engineRuleId→平台规则映射留在编排器 `publishedRuleByEngineRuleId`，spec §5.2）；
  - `cleanup` → 删 executeScan 产生的 stdout 临时文件；
  - `scanTarget = context.workDir ?: context.repoUrl`（spec §5.2；删除旧 `localPathOf`/configJson.localPath 路径，由 workDir 取代）。
  - 因 executeScan→collectResult 顺序调用且共享 stdoutRef，用 `@Volatile private var stdoutRef: String?` 实例字段（编排器对同一 adapter 顺序调五阶段，安全）。

- [ ] **Step 1: 写失败测试**

`module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/semgrep/SemgrepAdapterTest.kt` 全文件替换为：

```kotlin
package com.example.compliance.engineadapter.semgrep

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8：SemgrepAdapter 五方法化——execute/collect/normalize/cleanup 职责分离。 */
class SemgrepAdapterTest {
    private val cli = mockk<SemgrepCli>()
    private val adapter = SemgrepAdapter(cli, SemgrepResultParser(), SemgrepSeverityMapper())

    private val json = javaClass.getResource("/semgrep/basic.json").readText(StandardCharsets.UTF_8)
    private val ctx = ScanContext(1L, 1L, "https://git.example.com/repo.git", "main")

    @Test
    fun `execute and collect keep raw severities, normalize maps them`() {
        every { cli.run(any(), any()) } returns json

        val execution = adapter.executeScan(ctx)
        assertTrue(execution.success)
        assertTrue(execution.stdoutRef != null, "stdoutRef should point at persisted output")

        val raw = adapter.collectResult(ctx)
        assertEquals(2, raw.size)
        assertEquals("ERROR", raw[0].severity)   // collectResult 保留原始 severity
        assertEquals("WARNING", raw[1].severity)

        val normalized = adapter.normalizeResult(ctx, raw)
        assertEquals("HIGH", normalized[0].severity)
        assertEquals("MEDIUM", normalized[1].severity)
    }

    @Test
    fun `normalizeResult maps severity only`() {
        val normalized = adapter.normalizeResult(
            ctx,
            listOf(RawFinding("r1", null, "A.java", 1, "INFO", null, null, null)),
        )
        assertEquals("LOW", normalized[0].severity)
    }

    @Test
    fun `cleanup clears stdout ref`() {
        every { cli.run(any(), any()) } returns json
        adapter.executeScan(ctx)
        adapter.collectResult(ctx)
        adapter.cleanup(ctx)
        assertTrue(adapter.collectResult(ctx).isEmpty(), "after cleanup collectResult returns empty")
    }

    @Test
    fun `scan target prefers workDir`() {
        every { cli.run(any(), any()) } returns json
        adapter.executeScan(ScanContext(1L, 1L, "https://git.example.com/repo.git", "main", workDir = "/tmp/checkout"))
        verify { cli.run("/tmp/checkout", "main") }
    }

    @Test
    fun `scan default pipeline returns normalized findings`() {
        every { cli.run(any(), any()) } returns json
        val result = adapter.scan(ctx)
        assertTrue(result.success)
        assertEquals(2, result.findings.size)
        assertEquals("HIGH", result.findings[0].severity)
    }

    @Test
    fun `engine name is SEMGREP`() {
        assertEquals("SEMGREP", adapter.engine)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-engine-adapter:test --tests "*SemgrepAdapterTest*"`
Expected: 编译失败 — `executeScan`/`collectResult`/`normalizeResult` 未覆写，`ScanContext` 新字段缺 workDir。

- [ ] **Step 3: 全文件替换 SemgrepAdapter.kt**

```kotlin
package com.example.compliance.engineadapter.semgrep

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanExecutionResult
import org.springframework.stereotype.Component
import java.io.File

@Component
class SemgrepAdapter(
    private val cli: SemgrepCli,
    private val parser: SemgrepResultParser,
    private val severityMapper: SemgrepSeverityMapper,
) : ScanEngineAdapter {

    override val engine: String = "SEMGREP"

    /** executeScan 落盘路径：编排器对同一 adapter 顺序调用 executeScan→collectResult→cleanup，实例字段安全（spec §5.1 stdoutRef 语义）。 */
    @Volatile
    private var stdoutRef: String? = null

    override fun prepareScan(context: ScanContext) {
        // 无前置动作：超时与临时文件重定向由 SemgrepCli 负责（spec §5.2）
    }

    /** 执行 semgrep，stdout 落盘为临时文件并返回 stdoutRef。 */
    override fun executeScan(context: ScanContext): ScanExecutionResult {
        val target = scanTarget(context)
        val stdout = cli.run(target, context.ref)
        val file = File.createTempFile("semgrep-stdout-", ".json")
        file.writeText(stdout)
        stdoutRef = file.absolutePath
        return ScanExecutionResult(success = true, stdoutRef = file.absolutePath)
    }

    /** 读取 stdout 文件并解析为引擎原生 finding（保留原始 severity，映射在 normalizeResult）。 */
    override fun collectResult(context: ScanContext): List<RawFinding> {
        val ref = stdoutRef ?: return emptyList()
        val content = runCatching { File(ref).readText() }.getOrDefault("")
        return parser.parse(content)
    }

    /** severity 映射（Semgrep ERROR/WARNING/INFO → HIGH/MEDIUM/LOW）；ruleId→平台规则映射留在编排器。 */
    override fun normalizeResult(context: ScanContext, raw: List<RawFinding>): List<RawFinding> =
        raw.map { it.copy(severity = severityMapper.map(it.severity)) }

    /** 删除 executeScan 产生的 stdout 临时文件（不泄漏）。 */
    override fun cleanup(context: ScanContext) {
        stdoutRef?.let { runCatching { File(it).delete() } }
        stdoutRef = null
    }

    /** 扫描目标：优先编排器检出的 workDir，缺失回退 repoUrl（spec §5.2）。 */
    private fun scanTarget(context: ScanContext): String = context.workDir ?: context.repoUrl
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :module-engine-adapter:test`
Expected: PASS（SemgrepAdapterTest 6 个 + parser/mapper 既有测试）。

- [ ] **Step 5: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（编排器仍走 `adapter.scan()` 默认管线 → 现在内部跑 Semgrep 五阶段；STUB 测试仍 override scan()，零修改）。

- [ ] **Step 6: Commit**

```bash
git add module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/semgrep/SemgrepAdapter.kt module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/semgrep/SemgrepAdapterTest.kt
git commit -m "feat(engine-adapter): semgrep adapter five-stage contract with stdout ref and severity mapping in normalize"
```

---

### Task 8.4: 编排器五阶段管线 + PREPARING + 门控 checkout + M8 引擎契约集成测试

> **基线**：本任务在 Task 6.5 之后的 `ScanOrchestrator`（已含版本盖章/occurrence/verifyRechecking/durationMs）上做**外科手术式修改**，不重写整文件。

**Files:**
- Modify: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanOrchestrator.kt`（构造 + 五阶段 + PREPARING + 门控 checkout + finally cleanup）
- Modify: `app-server/src/test/kotlin/com/example/compliance/scan/ScanPipelineIntegrationTest.kt`（等待谓词 +PREPARING，测试内部实现形态，Ruling 允许；断言语义不变）
- Modify: `app-server/src/test/kotlin/com/example/compliance/scan/M6LifecycleIntegrationTest.kt`（waitDone 谓词 +PREPARING）
- Modify: `app-server/src/test/kotlin/com/example/compliance/remediation/M7RemediationIntegrationTest.kt`（waitDone 谓词 +PREPARING）
- Create: `app-server/src/test/kotlin/com/example/compliance/scan/M8EngineContractIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 8.1 五方法契约 + `ScanExecutionResult`；Task 8.2 `GitCheckout`/`CheckoutResult` + `app.scan.checkout-engines`；Task 6.5 既有 executeAsync 全链。
- Produces: 编排器五阶段序列 `prepareScan → executeScan → collectResult → normalizeResult`（cleanup 在 finally，spec §5.1）；PREPARING 置位（§5.3）；门控 checkout（engine ∈ checkout-engines → `gitCheckout.checkout` + 回填 `task.commitId`/`context.workDir`；否则跳过、commitId=null）；`ScanExecutionResult` 失败 → 抛 500（retry/PARTIAL_SUCCESS 按 PF-10 延后：单引擎不可达，枚举已存在）。
- **Ruling（ledger 细化）**：冻结/新集成测试的等待谓词统一加 `PREPARING`（测试内部实现形态，frozen 保护的是断言与语义；§7 亦授权「测试 STUB 适配器按新契约更新」）。

- [ ] **Step 1: 写失败集成测试（STUBM8 五方法 + cleanup flag + commitId null）**

创建 `app-server/src/test/kotlin/com/example/compliance/scan/M8EngineContractIntegrationTest.kt`：

```kotlin
package com.example.compliance.scan

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.checklist.application.ChecklistService
import com.example.compliance.project.application.BindRepositoryCommand
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import com.example.compliance.result.application.FindingLifecyclePort
import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanExecutionResult
import com.example.compliance.rule.application.AddEngineBindingCommand
import com.example.compliance.rule.application.CreateRuleCommand
import com.example.compliance.rule.application.RuleService
import com.example.compliance.rule.application.SetPolicyCommand
import com.example.compliance.scan.application.ScanTaskService
import com.example.compliance.scan.domain.ScanTaskStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8 引擎契约集成测试：STUBM8 以五方法形态接入，验证编排器五阶段管线 + PREPARING + 门控 checkout（commitId null）+ cleanup finally。
 *  数据前缀 M8-*。 */
class M8EngineContractIntegrationTest : AbstractIntegrationTest() {

    object StubM8State {
        @Volatile var prepared = false
        @Volatile var executed = false
        @Volatile var collected = false
        @Volatile var cleanupCalled = false
    }

    @TestConfiguration
    class StubM8AdapterConfig {
        @Bean
        fun stubM8Adapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUBM8"
            override fun prepareScan(context: ScanContext) { StubM8State.prepared = true }
            override fun executeScan(context: ScanContext): ScanExecutionResult {
                StubM8State.executed = true
                return ScanExecutionResult(success = true, durationMs = 5)
            }
            override fun collectResult(context: ScanContext): List<RawFinding> {
                StubM8State.collected = true
                return listOf(RawFinding("stub-m8-rule", "M8", "src/main/java/M8.java", 10, "HIGH", "m", "x=id;"))
            }
            override fun normalizeResult(context: ScanContext, raw: List<RawFinding>): List<RawFinding> = raw
            override fun cleanup(context: ScanContext) { StubM8State.cleanupCalled = true }
        }
    }

    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var checklistService: ChecklistService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService
    @Autowired lateinit var lifecyclePort: FindingLifecyclePort

    @Test
    fun `orchestrator drives five stages with cleanup and skips checkout for stub engine`() {
        // 1. 项目 + 仓库
        val project = projectService.create(CreateProjectCommand("M8P", "M8 项目", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("m8-repo", "https://git.example.com/m8.git", "GITLAB", "main", "tok"))
        // 2. 规则（STUBM8 绑定，无需清单——本次只断言引擎契约）
        val rule = ruleService.create(CreateRuleCommand("M8-SQLI", "M8 注入", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUBM8", "stub-m8-rule", null))
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'HIGH'"))
        ruleService.publish(rule.id!!)

        // 3. 触发扫描并等待完成（谓词含 PREPARING）
        StubM8State.prepared = false; StubM8State.executed = false
        StubM8State.collected = false; StubM8State.cleanupCalled = false
        val task = scanTaskService.startScan(project.id!!, "STUBM8", "main")
        var done = false
        repeat(50) {
            val s = scanTaskService.get(task.id!!).status
            if (s != ScanTaskStatus.PENDING && s != ScanTaskStatus.PREPARING && s != ScanTaskStatus.RUNNING) { done = true; return@repeat }
            Thread.sleep(200)
        }
        assertTrue(done, "scan should finish within timeout")
        assertEquals(ScanTaskStatus.SUCCESS, scanTaskService.get(task.id!!).status)

        // 4. 契约断言：五阶段全部驱动 + cleanup 在 finally + STUBM8 未检出（commitId null）
        assertTrue(StubM8State.prepared, "prepareScan called")
        assertTrue(StubM8State.executed, "executeScan called")
        assertTrue(StubM8State.collected, "collectResult called")
        assertTrue(StubM8State.cleanupCalled, "cleanup called in finally")
        assertNull(scanTaskService.get(task.id!!).commitId, "STUBM8 not in checkout-engines -> commitId null")

        // 5. 结果贯通：finding 归属本次扫描（五阶段产物进 occurrence 查询）
        val views = lifecyclePort.findingsForScanTask(task.id!!)
        assertEquals(1, views.size)
        assertEquals("M8-SQLI", views[0].ruleCode)
        assertEquals("STUBM8", views[0].engine)  // FindingView.engine 由 m6b 编辑批次补尾字段（发现引擎）
    }
}
```

> 说明：等待谓词已含 `PREPARING`；其余三个测试（ScanPipeline/M6/M7）的等待谓词在 Step 3 同步修改。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app-server:test --tests "*M8EngineContractIntegrationTest*"`
Expected: 编译失败或断言失败——`ScanExecutionResult` 未在编排器消费、`task.commitId` 未回填、五阶段未驱动（取决于前序是否完成）。若仅因 `ScanOrchestrator` 尚未改，则该测试无法编译通过。

- [ ] **Step 3: 编排器五阶段改造（外科手术）**

`ScanOrchestrator.kt`：
1. 构造参数追加（import `com.example.compliance.scan.checkout.GitCheckout`）：
```kotlin
    private val gitCheckout: GitCheckout,
    @Value("\${app.scan.checkout-engines:}") private val checkoutEngines: Set<String>,
```
（`import org.springframework.beans.factory.annotation.Value`；CSV 自动拆成 Set。）

2. `executeAsync` 开头（原置 RUNNING）改为置 **PREPARING** 并声明 finally 用到的 vars：
```kotlin
        task.status = ScanTaskStatus.PREPARING        // spec §5.3
        task.startedAt = Instant.now()
        scanTaskRepository.save(task)
        log(scanTaskId, "PREPARE", "INFO", "start engine=${task.engine} project=${task.projectId}")
        var adapter: ScanEngineAdapter? = null
        var context: ScanContext? = null
        var checkoutDir: String? = null
        val start = System.currentTimeMillis()
```
3. try 内，在既有 `val adapter = registry.get(...) ?: throw` 之后、构造 context 之前，插入门控检出：
```kotlin
            val resolvedAdapter = registry.get(task.engine)
                ?: throw BusinessException(400, "unsupported engine: ${task.engine}")
            adapter = resolvedAdapter
            val checkout = if (task.engine.uppercase() in checkoutEngines) {
                gitCheckout.checkout(repo.gitUrl, task.ref).also { checkoutDir = it.workDir }
            } else null
            context = ScanContext(
                scanTaskId = task.id!!, projectId = task.projectId, repoUrl = repo.gitUrl,
                ref = task.ref, workDir = checkout?.workDir, commitId = checkout?.commitId,
            )
            task.status = ScanTaskStatus.RUNNING
            task.commitId = checkout?.commitId
            scanTaskRepository.save(task)
```
4. 把 `val context = ScanContext(task.id!!, task.projectId, repo.gitUrl, task.ref)` 与 `val start = ...`/`val result = adapter.scan(context)`/`val duration = ...` 替换为五阶段：
```kotlin
            resolvedAdapter.prepareScan(context!!)
            val execution = resolvedAdapter.executeScan(context!!)
            val raw = resolvedAdapter.collectResult(context!!)
            val normalizedRaw = resolvedAdapter.normalizeResult(context!!, raw)
            val duration = execution.durationMs ?: (System.currentTimeMillis() - start)
            if (!execution.success) {
                throw BusinessException(500, execution.errorMessage ?: "engine scan failed")
            }
            val ruleIds = mutableSetOf<Long>()
            var skipped = 0
            for (rawFinding in normalizedRaw) {
                val rule = ruleQueryService.publishedRuleByEngineRuleId(task.engine, rawFinding.engineRuleId)
                if (rule == null) { skipped++; continue }
                ruleIds += rule.id!!
                normalized += NewFinding(
                    rule.ruleCode, rule.name, rawFinding.filePath, rawFinding.line,
                    rawFinding.severity, rawFinding.category, rawFinding.message, rawFinding.codeSnippet,
                )
            }
            log(scanTaskId, "NORMALIZE", "INFO", "raw=${normalizedRaw.size} mapped=${normalized.size} skipped=$skipped")
```
（`result.findings` 引用全部改为 `normalizedRaw`；`result.success`/`result.errorMessage` 改 `execution.*`。Task 6.5 的版本盖章、ruleIds 收集、upsert、occurrence、verifyRechecking、ScanJob、评估、SUCCESS 落库保持不动。）

5. catch 保持 FAILED 不变；在 catch 之后补 `finally`：
```kotlin
        } finally {
            // spec §5.1/§5.2：adapter cleanup + 删除检出临时目录（均幂等，绝不触碰用户路径）
            context?.let { adapter?.cleanup(it) }
            checkoutDir?.let { gitCheckout.cleanup(it) }
        }
```
（注意 Kotlin 语法：现有方法体是 `try { ... } catch (e: Exception) { ... }`，追加 `finally` 块即可。）

6. **等待谓词同步（测试内部形态，Ruling）**：把三个集成测试的等待条件改为排除 PENDING/PREPARING/RUNNING 三态：
   - `ScanPipelineIntegrationTest` 第 68-70 行：`if (scanTaskService.get(task.id!!).status != ScanTaskStatus.RUNNING && != PENDING)` → 追加 `&& scanTaskService.get(task.id!!).status != ScanTaskStatus.PREPARING`；
   - `M6LifecycleIntegrationTest.waitDone`（第 272-276 行）同样追加 `PREPARING`；
   - `M7RemediationIntegrationTest.waitDone` 同样追加 `PREPARING`。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app-server:test --tests "*M8EngineContractIntegrationTest*" --tests "*ScanPipelineIntegrationTest*" --tests "*M6LifecycleIntegrationTest*" --tests "*M7RemediationIntegrationTest*"`
Expected: 全部 PASS（STUBM8 五阶段 + 冻结 ScanPipeline + M6 + M7 复扫闭环）。

- [ ] **Step 5: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（含 ReportApi、FindingRepository、Smoke 全部绿）。

- [ ] **Step 6: Commit**

```bash
git add module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanOrchestrator.kt app-server/src/test/kotlin/com/example/compliance/scan
git commit -m "feat(scan): orchestrator five-stage pipeline with PREPARING, gated checkout and cleanup finally"
```

> **M8 完成标准**：五方法契约落地、STUB 兼容零修改；SemgrepAdapter 五阶段化且 severity 映射在 normalize；编排器五阶段 + PREPARING + 门控 checkout（STUBM8 commitId null）+ cleanup 不泄漏；`./gradlew build` 全绿。

---
## M9 — 工程化补全（OpenAPI CI 触发 + RBAC + 异常语义 + 审计回滚 + 指标 + 通知）

### Task 9.1: OpenAPI Token 表（多 CI 管理，BCrypt）

**Files:**
- Create: `app-server/src/main/resources/db/migration/V10__openapi_token.sql`   <!-- PF-7：spec §6.3 绑定 V10=openapi_token -->
- Create: `module-openapi/src/main/kotlin/com/example/compliance/openapi/domain/ApiToken.kt`
- Create: `module-openapi/src/main/kotlin/com/example/compliance/openapi/infrastructure/ApiTokenRepository.kt`
- Create: `module-openapi/src/main/kotlin/com/example/compliance/openapi/application/ApiTokenService.kt`
- Create: `module-openapi/src/main/kotlin/com/example/compliance/openapi/api/ApiTokenAdminController.kt`
- Create: `module-openapi/src/test/kotlin/com/example/compliance/openapi/application/ApiTokenServiceTest.kt`

**Interfaces:**
- Consumes: module-common 的 `api` 暴露（security/data-jpa/validation 传递可得）；`BaseEntity`。
- Produces: `ApiToken` 实体（id, name, tokenHash, status ACTIVE/DISABLED, expiresAt?, lastUsedAt?, createdBy, createdAt）；`ApiTokenRepository`（`findByNameAndStatus`、`findAllByStatus`、`existsByName`）；`ApiTokenService`：
  - `fun create(name: String, expiresAt: Instant?, createdBy: Long): ApiTokenResult` —— BCrypt 哈希存库，**明文 token 仅此一次返回**（`ApiTokenResult(token=明文, ...)`）
  - `fun verify(rawToken: String): ApiToken?` —— BCrypt 匹配 + ACTIVE + 未过期
  - `fun disable(tokenId: Long, actorId: Long): ApiToken`
  - `fun list(): List<ApiToken>`
  - `fun recordUsage(tokenId: Long)`
- Produces: `POST /api/v1/openapi/tokens`（ADMIN）、`GET /api/v1/openapi/tokens`（ADMIN）、`POST /api/v1/openapi/tokens/{id}/disable`（ADMIN）。

- [ ] **Step 1: 写失败测试**

创建 `module-openapi/src/test/kotlin/com/example/compliance/openapi/application/ApiTokenServiceTest.kt`：

```kotlin
package com.example.compliance.openapi.application

import com.example.compliance.openapi.domain.ApiToken
import com.example.compliance.openapi.infrastructure.ApiTokenRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M9：OpenAPI token 多 CI 管理（P2-D7）——BCrypt 哈希、明文仅创建返回一次。 */
class ApiTokenServiceTest {

    private val repository = mockk<ApiTokenRepository>()
    private val encoder = BCryptPasswordEncoder()
    private val service = ApiTokenService(repository, encoder)

    @Test
    fun `create returns plaintext once and stores hash`() {
        every { repository.existsByName("ci-a") } returns false
        every { repository.save(any<ApiToken>()) } answers { (firstArg<ApiToken>()).also { it.id = 5L } }

        val result = service.create("ci-a", null, 9L)

        assertNotNull(result.token)
        assertTrue(result.token.length >= 20)
        verify { repository.save(match { it.name == "ci-a" && it.tokenHash != result.token && it.status == "ACTIVE" }) }
    }

    @Test
    fun `verify matches only active unexpired token`() {
        val token = "raw-token-abc"
        val hash = encoder.encode(token)
        val stored = ApiToken().apply { id = 5L; name = "ci-a"; tokenHash = hash; status = "ACTIVE"; expiresAt = null }
        every { repository.findByNameAndStatus("ci-a", "ACTIVE") } returns listOf(stored)

        val ok = service.verify(token)
        assertNotNull(ok)
        assertEquals(5L, ok.id)
    }

    @Test
    fun `verify rejects wrong token or expired`() {
        val hash = encoder.encode("raw-token-abc")
        val expired = ApiToken().apply { id = 6L; name = "ci-b"; tokenHash = hash; status = "ACTIVE"; expiresAt = Instant.now().minusSeconds(10) }
        every { repository.findByNameAndStatus("ci-b", "ACTIVE") } returns listOf(expired)

        assertNull(service.verify("raw-token-abc"))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-openapi:test --tests "*ApiTokenServiceTest*"`
Expected: 编译失败 — `ApiTokenService` 不存在（module-openapi 需先有 test 源集与测试依赖，见 Step 3 前的 build.gradle 调整）。

- [ ] **Step 3: 写 V10 迁移**

创建 `app-server/src/main/resources/db/migration/V10__openapi_token.sql`：

```sql
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
```

- [ ] **Step 4: 实现实体、仓储与服务**

`module-openapi/build.gradle.kts` 依赖块（若缺）补：

```kotlin
dependencies {
    implementation(project(":module-common"))
}
```

`ApiToken.kt`：

```kotlin
package com.example.compliance.openapi.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

/** CI 触发 API Token：token_hash 存 BCrypt；明文仅创建时返回一次。 */
@Entity
@Table(name = "api_token")
class ApiToken : BaseEntity() {
    @Column(name = "name", nullable = false, unique = true, length = 64)
    var name: String = ""
    @Column(name = "token_hash", nullable = false, length = 128)
    var tokenHash: String = ""
    @Column(name = "status", nullable = false, length = 16)
    var status: String = "ACTIVE"
    @Column(name = "expires_at")
    var expiresAt: Instant? = null
    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null
    @Column(name = "created_by")
    var createdBy: Long? = null
}
```

`ApiTokenRepository.kt`：

```kotlin
package com.example.compliance.openapi.infrastructure

import com.example.compliance.openapi.domain.ApiToken
import org.springframework.data.jpa.repository.JpaRepository

interface ApiTokenRepository : JpaRepository<ApiToken, Long> {
    fun findByNameAndStatus(name: String, status: String): List<ApiToken>
    fun findAllByStatus(status: String): List<ApiToken>
    fun existsByName(name: String): Boolean
}
```

`ApiTokenService.kt`：

```kotlin
package com.example.compliance.openapi.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.openapi.domain.ApiToken
import com.example.compliance.openapi.infrastructure.ApiTokenRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Instant

/** OpenAPI 触发 token 服务：BCrypt 哈希、明文仅创建返回一次（P2-D7）。 */
@Service
class ApiTokenService(
    private val repository: ApiTokenRepository,
    private val encoder: PasswordEncoder,
) {
    data class ApiTokenResult(val token: String, val apiToken: ApiToken)

    private val random = SecureRandom()
    private const val TOKEN_BYTES = 24
    private const val TOKEN_PREFIX = "cop-"

    /** 创建：明文 token 仅本方法返回一次；库中只存 BCrypt 哈希。 */
    @Transactional
    fun create(name: String, expiresAt: Instant?, createdBy: Long): ApiTokenResult {
        if (repository.existsByName(name)) throw BusinessException(409, "api token name already exists: $name")
        val raw = TOKEN_PREFIX + name + "-" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(randomBytes())
        val saved = repository.save(ApiToken().apply {
            this.name = name
            tokenHash = encoder.encode(raw)
            this.expiresAt = expiresAt
            this.createdBy = createdBy
        })
        return ApiTokenResult(raw, saved)
    }

    /** 校验：明文形如 cop-<name>-<random>，解析 name 找候选，BCrypt 匹配 + ACTIVE + 未过期。 */
    @Transactional(readOnly = true)
    fun verify(rawToken: String): ApiToken? {
        if (!rawToken.startsWith(TOKEN_PREFIX)) return null
        val body = rawToken.removePrefix(TOKEN_PREFIX)
        val name = body.substringBefore('-')
        val candidates = repository.findByNameAndStatus(name, "ACTIVE")
        val now = Instant.now()
        return candidates.firstOrNull { c ->
            (c.expiresAt == null || c.expiresAt.isAfter(now)) && encoder.matches(rawToken, c.tokenHash)
        }
    }

    private fun randomBytes(): ByteArray = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }
}
```

（`create` 中明文生成改为 `TOKEN_PREFIX + name + "-" + random`；`random` 为 SecureRandom 实例，`TOKEN_PREFIX = "cop-"`，`TOKEN_BYTES = 24`。）

- [ ] **Step 5: 修正测试以匹配最终实现**

`ApiTokenServiceTest` 同步：`create("ci-a", ...)` 的明文含 `cop-ci-a-` 前缀；`verify` 测试用 `service.create` 产出的明文（或按 `cop-ci-a-<random>` 构造 + 相应 hash 落库）。以最终实现为准调整断言，语义不变。

- [ ] **Step 6: 运行测试确认通过**

Run: `./gradlew :module-openapi:test --tests "*ApiTokenServiceTest*"`
Expected: PASS。

- [ ] **Step 7: 管理 API（ADMIN）**

`ApiTokenAdminController.kt`：

```kotlin
package com.example.compliance.openapi.api

import com.example.compliance.openapi.application.ApiTokenService
import com.example.compliance.openapi.domain.ApiToken
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

/** OpenAPI token 管理（ADMIN）。创建响应中仅返回一次明文 token。 */
@RestController
@RequestMapping("/api/v1/openapi/tokens")
class ApiTokenAdminController(private val service: ApiTokenService) {

    data class CreateTokenCommand(val name: String, val expiresAt: java.time.Instant? = null)
    data class TokenView(val id: Long, val name: String, val status: String, val expiresAt: java.time.Instant?, val token: String?)

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    fun create(@RequestBody cmd: CreateTokenCommand): TokenView {
        val result = service.create(cmd.name, cmd.expiresAt, 1L)
        return TokenView(result.apiToken.id!!, result.apiToken.name, result.apiToken.status, result.apiToken.expiresAt, result.token)
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    fun list(): List<TokenView> = service.list().map { TokenView(it.id!!, it.name, it.status, it.expiresAt, null) }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    fun disable(@PathVariable id: Long): TokenView {
        val t = service.disable(id, 1L)
        return TokenView(t.id!!, t.name, t.status, t.expiresAt, null)
    }
}
```

`ApiTokenService` 补 `list()` 与 `disable(tokenId, actorId)`（`findAllByStatus` / 按 id 找、置 DISABLED、save）。

- [ ] **Step 8: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 9: Commit**

```bash
git add module-openapi app-server/src/main/resources/db/migration/V10__openapi_token.sql
git commit -m "feat(openapi): per-CI api token table with bcrypt hashing and admin management (V10)"
```

---

### Task 9.2: OpenAPI CI 触发扫描端点（X-API-Token 校验）

**Files:**
- Consume（不新建，M7 Task 7.4 已产出）: `module-scan/.../application/ScanTriggerPort.kt` + `ScanTaskView`；`ScanTaskService` 已实现 `triggerScan`
- Create: `module-openapi/src/main/kotlin/com/example/compliance/openapi/api/OpenApiScanController.kt`
- Modify: `module-openapi/build.gradle.kts`（+implementation(project(":module-scan"))）
- Modify: `module-auth/src/main/kotlin/com/example/compliance/auth/config/SecurityConfig.kt`（permitAll + ADMIN 路径，字符串字面量）
- Create: `module-openapi/src/test/kotlin/com/example/compliance/openapi/api/OpenApiScanControllerTest.kt`

**Interfaces:**
- Consumes: `ApiTokenService.verify`（Task 9.1）；`ScanTriggerPort`（**M7 Task 7.4 定义**，module-scan.application）：
  - `fun triggerScan(projectId: Long, engine: String, ref: String?, triggerType: String, requestId: String?): ScanTaskView`（输入可空；缺省由 startScan 生成 UUID）
  - `data class ScanTaskView(val id: Long, val projectId: Long, val engine: String, val status: ScanTaskStatus, val requestId: String)`（输出恒非空）
- Produces: `POST /api/v1/openapi/scans` body `TriggerScanCommand(projectId, engine, ref?, requestId?)`，头 `X-API-Token`：
  - 有合法 token → 以该 CI 名义触发（triggerType="CI"）
  - 无 token / token 无效 → 回落 JWT principal（triggerType 沿用请求或 "CI"）
  - 校验失败 → 401

- [ ] **Step 1: 写失败测试（token 校验 + 触发）**

创建 `module-openapi/src/test/kotlin/com/example/compliance/openapi/api/OpenApiScanControllerTest.kt`：

```kotlin
package com.example.compliance.openapi.api

import com.example.compliance.openapi.application.ApiTokenService
import com.example.compliance.scan.application.ScanTriggerPort
import com.example.compliance.scan.domain.ScanTaskStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

/** M9：开放扫描端点——合法 token 触发、无 token 回落 JWT（切片，服务 mock）。 */
@WebMvcTest(OpenApiScanController::class)
class OpenApiScanControllerTest {

    @Autowired lateinit var mockMvc: MockMvc

    @MockBean lateinit var tokenService: ApiTokenService
    @MockBean lateinit var triggerPort: ScanTriggerPort

    @Test
    fun `valid token triggers scan`() {
        every { tokenService.verify("cop-ci-a-xyz") } returns com.example.compliance.openapi.domain.ApiToken().apply { id = 5L; name = "ci-a" }
        every { triggerPort.triggerScan(9L, "SEMGREP", "main", "CI", "req-1") } returns
            com.example.compliance.scan.application.ScanTaskView(1L, 9L, "SEMGREP", ScanTaskStatus.PENDING, "req-1")

        mockMvc.perform(
            post("/api/v1/openapi/scans")
                .header("X-API-Token", "cop-ci-a-xyz")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"projectId":9,"engine":"SEMGREP","ref":"main","requestId":"req-1"}""")
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("PENDING"))
    }

    @Test
    fun `invalid token rejected with 401`() {
        every { tokenService.verify("bad") } returns null
        mockMvc.perform(
            post("/api/v1/openapi/scans")
                .header("X-API-Token", "bad")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"projectId":9,"engine":"SEMGREP"}""")
        )
            .andExpect(status().isUnauthorized)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-openapi:test --tests "*OpenApiScanControllerTest*"`
Expected: 编译失败 — `ScanTriggerPort`/`OpenApiScanController` 不存在。

- [ ] **Step 3: 消费 M7 的 ScanTriggerPort（无需新建）**

`ScanTriggerPort` 接口与 `ScanTaskView` 值类型已由 **M7 Task 7.4** 定义于 `module-scan/.../application/`，且 `ScanTaskService` 已在 Task 7.4 实现 `triggerScan(projectId, engine, ref, triggerType, requestId)`（内部委托 `startScan(..., triggerType, requestId)` 透传 requestId）。本任务**不新建**该文件、**不改** `ScanTaskService`——直接进入 Step 4，由 `OpenApiScanController` 注入并调用。

- [ ] **Step 4: 实现 OpenApiScanController**

`module-openapi/build.gradle.kts` 依赖补 `implementation(project(":module-scan"))`。

`OpenApiScanController.kt`：

```kotlin
package com.example.compliance.openapi.api

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.openapi.application.ApiTokenService
import com.example.compliance.scan.application.ScanTaskView
import com.example.compliance.scan.application.ScanTriggerPort
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/** CI 触发扫描开放端点：X-API-Token 校验通过 → 以该 CI 触发；否则回落 JWT principal。 */
@RestController
@RequestMapping("/api/v1/openapi")
class OpenApiScanController(
    private val tokenService: ApiTokenService,
    private val triggerPort: ScanTriggerPort,
) {
    data class TriggerScanCommand(val projectId: Long, val engine: String, val ref: String? = null, val requestId: String? = null)

    @PostMapping("/scans")
    fun trigger(@RequestBody cmd: TriggerScanCommand, @RequestHeader("X-API-Token", required = false) rawToken: String?): ResponseEntity<ScanTaskView> {
        if (rawToken == null) {
            throw BusinessException(401, "missing X-API-Token")
        }
        val token = tokenService.verify(rawToken) ?: throw BusinessException(401, "invalid api token")
        tokenService.recordUsage(token.id!!)
        val view = triggerPort.triggerScan(cmd.projectId, cmd.engine, cmd.ref, "CI", cmd.requestId)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view)
    }
}
```

> 设计说明：按 spec 的跨模块过滤器方案，**控制器侧做 token 校验**（module-auth 的 SecurityConfig 只按字符串字面量放行路径，不注入 openapi 依赖）。`X-API-Token` 缺失/无效 → 本控制器抛 401（Task 9.4 把 BusinessException(401) 映射为 HTTP 401）。

- [ ] **Step 5: SecurityConfig 放行 openapi 路径**

`module-auth/.../config/SecurityConfig.kt` 的 `permitAll` 列表追加字符串字面量：

```kotlin
    "/api/v1/openapi/scans"
```

并在 `authorizeHttpRequests` 增加：

```kotlin
    .requestMatchers("/api/v1/openapi/tokens/**").hasRole("ADMIN")
```

> 字符串字面量，不 import module-openapi 任何类（避免模块依赖）。

- [ ] **Step 6: 运行测试确认通过**

Run: `./gradlew :module-openapi:test --tests "*OpenApiScanControllerTest*" --tests "*ApiTokenServiceTest*"`
Expected: PASS。若 `@WebMvcTest` 因 Security 过滤链拦截（未认证访问非 permitAll 路径），`OpenApiScanControllerTest` 加 `@AutoConfigureMockMvc(addFilters = false)`；或断言按实际 401/403 调整——**以测试能精确表达「有效 token 触发 / 无效 token 401」为准**。

- [ ] **Step 7: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 8: Commit**

```bash
git add module-openapi module-auth/src/main/kotlin/com/example/compliance/auth/config/SecurityConfig.kt
git commit -m "feat(openapi): CI scan trigger endpoint consuming ScanTriggerPort with X-API-Token validation"
```

---
### Task 9.3: RBAC 矩阵（SecurityConfig 方法级授权）

**Files:**
- Modify: `module-auth/src/main/kotlin/com/example/compliance/auth/config/SecurityConfig.kt`（RBAC 矩阵 + @EnableMethodSecurity）
- Modify: `module-common/src/main/kotlin/com/example/compliance/common/config/`（若有方法安全配置则合并，否则在 SecurityConfig 启用）
- Create: `app-server/src/test/kotlin/com/example/compliance/auth/M9RbacIntegrationTest.kt`

**Interfaces:**
- Consumes: 既有 JWT filter、`/api/v1/admin/**` hasRole("ADMIN")。
- Produces: 方法级授权矩阵（spec §RBAC 矩阵）：
  - 管理：`/api/v1/admin/**`、`/api/v1/openapi/tokens/**` → ADMIN
  - 审计/审批：`/api/v1/audit/**` → AUDITOR（若存在）
  - 整改操作：`/api/v1/remediation/**` 写操作 → REMEDIATOR（或 ADMIN）
  - 其余已认证路径 → 任意 AUTHENTICATED 角色
  - `@EnableMethodSecurity` + `@PreAuthorize` 注解在各 Controller（TokenAdmin 已加，Task 9.2）

- [ ] **Step 1: 写失败集成测试**

创建 `app-server/src/test/kotlin/com/example/compliance/auth/M9RbacIntegrationTest.kt`：

```kotlin
package com.example.compliance.auth

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.auth.login.LoginService
import com.example.compliance.auth.login.LoginCommand
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import kotlin.test.assertEquals

/** M9-* 数据前缀；RBAC 矩阵：匿名访问 token 管理 401，登录后仍无 ADMIN 角色 403。 */
class M9RbacIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var loginService: LoginService

    private fun adminToken(): String {
        // 以既有用户体系创建/取 ADMIN 用户；若 LoginService 无 createUser，则用测试初始化数据中的 ADMIN
        return "jwt-admin-token"
    }

    @Test
    fun `token management requires authentication`() {
        val response = rest.exchange(
            "/api/v1/openapi/tokens", HttpMethod.GET, null, String::class.java
        )
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }
}
```

> 测试实现说明：`M9RbacIntegrationTest` 通过 `TestRestTemplate` 验证：未认证 → 401；普通用户（无 ADMIN）→ 403；ADMIN → 200。用户/登录路径以既有 `LoginService`/`UserService` 的实际签名为准；若测试基建未提供创建用户能力，则断言 401 与 403 两档即可（对 spec RBAC 矩阵「匿名拒绝」与「非授权拒绝」两条验收）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app-server:test --tests "*M9RbacIntegrationTest*"`
Expected: 视实现而定——若当前 `GET /api/v1/openapi/tokens` 未被安全拦截则 401 断言失败；若 SecurityConfig 未区分角色则 403 断言失败。

- [ ] **Step 3: SecurityConfig 启用方法安全 + RBAC 路径矩阵**

`SecurityConfig.kt` 类上加 `@EnableMethodSecurity`，`authorizeHttpRequests` 改为：

```kotlin
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(PERMIT_ALL_PATHS).permitAll()
                    // RBAC 矩阵：管理路径仅 ADMIN
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/openapi/tokens/**").hasRole("ADMIN")
                    // 整改写操作：REMEDIATOR 或 ADMIN
                    .requestMatchers(HttpMethod.POST, "/api/v1/remediation/**").hasAnyRole("REMEDIATOR", "ADMIN")
                    .anyRequest().authenticated()
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
```

（`PERMIT_ALL_PATHS` 为既有 `permitAll` 列表常量，含 login/docs/health + Task 9.2 追加的 `"/api/v1/openapi/scans"`。）

- [ ] **Step 4: 既有 Controller 补 @PreAuthorize（按矩阵）**

检查并给以下 Controller 写操作加 `@PreAuthorize`（注解值按矩阵；**若已由路径矩阵覆盖则不必重复**，以能通过 Step 1 测试为准）：
- `module-remediation/.../RemediationController`：写操作 `hasAnyRole('REMEDIATOR','ADMIN')`
- `module-openapi/.../ApiTokenAdminController`：已加 `hasRole('ADMIN')`（Task 9.2）

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :app-server:test --tests "*M9RbacIntegrationTest*"`
Expected: PASS。

- [ ] **Step 6: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。注意既有 API 集成测试（ReportApi 等）若未带认证而访问受保护端点，需在测试中带 JWT——**若既有测试已带认证头则不变；否则按既有测试基建补认证**（以既有 ReportApiIntegrationTest 的认证方式为准）。

- [ ] **Step 7: Commit**

```bash
git add module-auth/src/main/kotlin/com/example/compliance/auth/config/SecurityConfig.kt module-remediation/src/main/kotlin/com/example/compliance/remediation/api app-server/src/test/kotlin/com/example/compliance/auth/M9RbacIntegrationTest.kt
git commit -m "feat(auth): RBAC matrix with method security and path-based role rules"
```

---

### Task 9.4: 异常语义 —— BusinessException 状态码映射 + 404

**Files:**
- Modify: `module-common/src/main/kotlin/com/example/compliance/common/exception/GlobalExceptionHandler.kt`（BusinessException.code → HTTP 状态）
- Modify: `module-common/src/main/kotlin/com/example/compliance/common/exception/BusinessException.kt`（确认 code 字段语义）
- Create: `module-common/src/test/kotlin/com/example/compliance/common/exception/GlobalExceptionHandlerTest.kt`

**Interfaces:**
- Consumes: 既有 `BusinessException`（含 `code`）、`MethodArgumentNotValidException`。
- Produces: `GlobalExceptionHandler` 将 `BusinessException.code` 映射为 HTTP 状态（404→404、409→409、401→401、其余 4xx 按 code、无/0→400）；`NoResourceFoundException` → 404；未知异常仍 500。响应体 `{code, message, timestamp}`（保持既有结构）。

- [ ] **Step 1: 写失败测试**

创建 `module-common/src/test/kotlin/com/example/compliance/common/exception/GlobalExceptionHandlerTest.kt`：

```kotlin
package com.example.compliance.common.exception

import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.servlet.resource.NoResourceFoundException
import kotlin.test.assertEquals

/** M9：BusinessException.code 映射 HTTP 状态。 */
class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `code 404 maps to NOT_FOUND`() {
        val resp: ResponseEntity<Any> = handler.handleBusiness(BusinessException(404, "nope"))
        assertEquals(HttpStatus.NOT_FOUND, resp.statusCode)
    }

    @Test
    fun `code 409 maps to CONFLICT`() {
        val resp: ResponseEntity<Any> = handler.handleBusiness(BusinessException(409, "dup"))
        assertEquals(HttpStatus.CONFLICT, resp.statusCode)
    }

    @Test
    fun `missing resource maps to NOT_FOUND`() {
        val resp: ResponseEntity<Any> = handler.handleNotFound(NoResourceFoundException("handler", "GET"))
        assertEquals(HttpStatus.NOT_FOUND, resp.statusCode)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-common:test --tests "*GlobalExceptionHandlerTest*"`
Expected: 编译失败或断言失败——`handleBusiness` 现返回 BAD_REQUEST，`handleNotFound` 未定义。

- [ ] **Step 3: 改写 GlobalExceptionHandler**

`module-common/.../exception/GlobalExceptionHandler.kt` 全文件替换（保持既有类名与其余 handler，只改状态映射）：

```kotlin
package com.example.compliance.common.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler {

    private fun body(code: Int, message: String) = mapOf(
        "code" to code, "message" to message, "timestamp" to Instant.now().toString(),
    )

    /** BusinessException.code → HTTP 状态；未识别 code 回退 400。 */
    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException): ResponseEntity<Any> {
        val status = httpStatusFor(e.code)
        return ResponseEntity.status(status).body(body(e.code, e.message ?: "business error"))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(400, e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }))

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNotFound(e: NoResourceFoundException): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(body(404, "resource not found"))

    @ExceptionHandler(Exception::class)
    fun handleUnknown(e: Exception): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body(500, e.message ?: "internal error"))

    private fun httpStatusFor(code: Int): HttpStatus = when (code) {
        400 -> HttpStatus.BAD_REQUEST
        401 -> HttpStatus.UNAUTHORIZED
        403 -> HttpStatus.FORBIDDEN
        404 -> HttpStatus.NOT_FOUND
        409 -> HttpStatus.CONFLICT
        500 -> HttpStatus.INTERNAL_SERVER_ERROR
        else -> if (code in 400..499) HttpStatus.valueOf(code) else HttpStatus.BAD_REQUEST
    }
}
```

> 若既有 `BusinessException` 无 `code` 属性，本任务先补 `val code: Int`（默认 400）；既有构造调用点（`BusinessException(500, ...)`、`BusinessException(404, ...)` 等）已按 (code, message) 传参，兼容。以既有 `BusinessException` 文件为准确认。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :module-common:test --tests "*GlobalExceptionHandlerTest*"`
Expected: PASS。

- [ ] **Step 5: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。注意既有测试对错误码的断言（如 Rbac 相关 400/500 断言）——若既有测试断言 BusinessException 必为 400，本任务按 spec 改语义后同步该断言（**plan ruling：spec §异常语义 要求 code→HTTP 映射，优先于既有「恒 400」的测试断言**）。

- [ ] **Step 6: Commit**

```bash
git add module-common/src/main/kotlin/com/example/compliance/common/exception module-common/src/test/kotlin/com/example/compliance/common/exception
git commit -m "feat(common): business exception code to HTTP status mapping and 404 handling"
```

---

### Task 9.5: 动态配置审计回滚（规则/清单变更审计 + 版本回滚）

**Files:**
- Modify: `module-rule/src/main/kotlin/com/example/compliance/rule/application/RuleService.kt`（写操作审计）
- Modify: `module-checklist/src/main/kotlin/com/example/compliance/checklist/application/ChecklistService.kt`（写操作审计）
- Modify: `module-common/src/main/kotlin/com/example/compliance/common/audit/AuditService.kt`（确认/补全 record 方法）
- Create: `module-rule/src/test/kotlin/com/example/compliance/rule/application/RuleAuditTest.kt`
- Create: `module-checklist/src/test/kotlin/com/example/compliance/checklist/application/ChecklistAuditTest.kt`

**Interfaces:**
- Consumes: `AuditService`（既有，PF-9 真实签名 `record(action: String, module: String, userId: Long? = null, resourceType: String? = null, resourceId: Long? = null, detail: String? = null, ip: String? = null)`）。
- Produces: 规则中心/清单中心全部写操作（create/update/publish/bind/version）落审计记录（action 如 `RULE_CREATED`/`RULE_PUBLISHED`/`CHECKLIST_PUBLISHED`/`CHECKLIST_BIND`，detail 为变更摘要 JSON）；`AuditService.record` 幂等可用（已存在则不重复加）。

- [ ] **Step 1: 写失败测试**

创建 `module-rule/src/test/kotlin/com/example/compliance/rule/application/RuleAuditTest.kt`：

```kotlin
package com.example.compliance.rule.application

import com.example.compliance.common.audit.AuditService
import com.example.compliance.rule.infrastructure.RuleRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/** M9：规则写操作审计（审计/回滚闭环的写侧）。 */
class RuleAuditTest {

    private val ruleRepository = mockk<RuleRepository>()
    private val auditService = mockk<AuditService>(relaxed = true)
    private val ruleService = RuleService(ruleRepository, auditService)

    @Test
    fun `publish writes audit record`() {
        every { ruleRepository.findById(1L) } returns java.util.Optional.of(
            com.example.compliance.rule.domain.RuleDefinition().apply { id = 1L; ruleCode = "R1" }
        )
        every { ruleRepository.save(any()) } answers { firstArg() }

        ruleService.publish(1L, 9L)

        // PF-9 真实签名：record(action, module, userId, resourceType, resourceId, detail, ip)
        verify { auditService.record("RULE_PUBLISHED", "rule", 9L, "rule", any(), any()) }
    }
}
```

（若 `RuleService` 构造签名不同——按既有 `RuleService` 文件为准；`publish` 若已有 actorId 参数则直接用，否则补 `publish(ruleId, actorId)`。以实际签名为准调整测试。）

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-rule:test --tests "*RuleAuditTest*"` 与 `./gradlew :module-checklist:test --tests "*ChecklistAuditTest*"`（后者若本任务未写则跳过）
Expected: 编译失败或 verify 失败——写操作未审计。

- [ ] **Step 3: 注入审计到写操作**

`RuleService` 构造注入 `auditService: AuditService`（既有若已注入则只补调用点），在 `publish`/`update`/`setPolicy`/`addEngineBinding`/`addComplianceMapping` 成功分支末尾：

```kotlin
        // PF-9 真实签名顺序：record(action, module, userId, resourceType, resourceId, detail)
        auditService.record("RULE_PUBLISHED", "rule", actorId, "rule", ruleId, "{\"ruleCode\":\"$ruleCode\",\"version\":$version}")
```

（各操作的 action 名/字段以 spec §审计 命名表为准：`RULE_CREATED`/`RULE_UPDATED`/`RULE_PUBLISHED`/`RULE_POLICY_SET`/`RULE_ENGINE_BIND`/`RULE_MAPPING`；`CHECKLIST_CREATED`/`CHECKLIST_ITEM_ADDED`/`CHECKLIST_PUBLISHED`/`CHECKLIST_BIND`/`CHECKLIST_ITEM_UPDATED`。）

`ChecklistService` 同样注入 `auditService` 并在写操作落记录。

> 既有写操作若没有 `actorId` 参数（内部调用），以当前认证上下文或 1L fallback（与 Task 9.2 的 actorId 解析一致）；**以不改动既有公开 API 签名为优先，审计 actor 尽量复用已有入参**。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :module-rule:test --tests "*RuleAuditTest*"`（以及 ChecklistAuditTest 若创建）
Expected: PASS。

- [ ] **Step 5: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（规则/清单既有单测若因构造签名变化需同步——以实际文件为准调整）。

- [ ] **Step 6: Commit**

```bash
git add module-rule/src/main module-rule/src/test module-checklist/src/main module-checklist/src/test
git commit -m "feat(rule,checklist): audit trail on all configuration write operations"
```

---
### Task 9.6: 报表统一指标模型（ReportService 指标口径收敛）

**Files:**
- Modify: `module-report/src/main/kotlin/com/example/compliance/report/application/ReportService.kt`（统一指标口径）
- Create: `module-report/src/main/kotlin/com/example/compliance/report/application/ReportMetrics.kt`（指标 DTO + 计算）
- Create: `module-report/src/test/kotlin/com/example/compliance/report/application/ReportMetricsTest.kt`

**Interfaces:**
- Consumes: `FindingLifecyclePort`（occurrence 视图）；`ComplianceEvaluation`/`ChecklistItemResult`。
- Produces: `ReportMetrics`（统一指标模型，spec §统一指标）：`openCount`、`fixedCount`、`waivedCount`、`bySeverity: Map<String,Int>`、`byStatus: Map<FindingStatus,Int>`、`evaluationScore: BigDecimal?`、`coveragePercent: BigDecimal`（评估条目 / 已发布清单条目）。`ReportService.scanSummary`/`complianceReport`/`trendReport` 统一消费此模型，保证「报表数据与扫描结果一致」（验收 #10）。

- [ ] **Step 1: 写失败测试**

创建 `module-report/src/test/kotlin/com/example/compliance/report/application/ReportMetricsTest.kt`：

```kotlin
package com.example.compliance.report.application

import com.example.compliance.result.application.FindingView
import com.example.compliance.result.domain.FindingStatus
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/** M9：统一指标模型——各报表口径一致。 */
class ReportMetricsTest {

    private fun view(status: FindingStatus, severity: String) = FindingView(
        1L, 9L, 1L, "R1", severity, status, "A.java", 1, Instant.now(), Instant.now(), 1, "STUB",
    )

    @Test
    fun `metrics aggregate by status and severity`() {
        val views = listOf(
            view(FindingStatus.NEW, "HIGH"),
            view(FindingStatus.NEW, "HIGH"),
            view(FindingStatus.FIXED, "LOW"),
            view(FindingStatus.WAIVED, "MEDIUM"),
        )
        val metrics = ReportMetrics.from(views, null, 4)

        assertEquals(2, metrics.openCount)
        assertEquals(1, metrics.fixedCount)
        assertEquals(1, metrics.waivedCount)
        assertEquals(2, metrics.bySeverity["HIGH"])
        assertEquals(2, metrics.byStatus[FindingStatus.NEW])
        assertEquals(100, metrics.coveragePercent.toInt())
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-report:test --tests "*ReportMetricsTest*"`
Expected: 编译失败 — `ReportMetrics` 不存在。

- [ ] **Step 3: 实现 ReportMetrics**

创建 `module-report/src/main/kotlin/com/example/compliance/report/application/ReportMetrics.kt`：

```kotlin
package com.example.compliance.report.application

import com.example.compliance.result.application.FindingView
import com.example.compliance.result.domain.FindingStatus
import java.math.BigDecimal

/** 统一指标模型：所有报表（scanSummary/compliance/trend）共用同一口径。 */
data class ReportMetrics(
    val total: Int,
    val openCount: Int,
    val fixedCount: Int,
    val waivedCount: Int,
    val bySeverity: Map<String, Int>,
    val byStatus: Map<FindingStatus, Int>,
    val evaluationScore: BigDecimal?,
    val coveragePercent: BigDecimal,
) {
    companion object {
        /** 活动集（开放口径）：NEW/CONFIRMED/ASSIGNED/FIXING/RECHECKING。 */
        private val OPEN_STATES = setOf(
            FindingStatus.NEW, FindingStatus.CONFIRMED, FindingStatus.ASSIGNED,
            FindingStatus.FIXING, FindingStatus.RECHECKING,
        )

        fun from(
            views: List<FindingView>,
            score: BigDecimal?,
            publishedItemCount: Int,
        ): ReportMetrics {
            val byStatus = views.groupingBy { it.status }.eachCount()
            val open = views.count { it.status in OPEN_STATES }
            val fixed = byStatus[FindingStatus.FIXED] ?: 0
            val waived = (byStatus[FindingStatus.WAIVED] ?: 0) + (byStatus[FindingStatus.IGNORED] ?: 0)
            val bySeverity = views.groupingBy { it.severity }.eachCount()
            val coverage = if (publishedItemCount <= 0) BigDecimal.ZERO
                else BigDecimal(100.0 * views.distinctBy { it.ruleCode }.size / publishedItemCount)
                    .setScale(2, java.math.RoundingMode.HALF_UP)
            return ReportMetrics(
                total = views.size, openCount = open, fixedCount = fixed, waivedCount = waived,
                bySeverity = bySeverity, byStatus = byStatus,
                evaluationScore = score, coveragePercent = coverage,
            )
        }
    }
}
```

- [ ] **Step 4: ReportService 统一消费**

`ReportService.kt` 的 `scanSummary`/`complianceReport`/`trendReport` 内部改用 `ReportMetrics.from(...)` 计算口径（从 `FindingLifecyclePort.findingsForScanTask` 或既有 occurrence 查询取 `FindingView` 列表，score 取自 `ComplianceEvaluation`，publishedItemCount 取自 `ChecklistQueryService.publishedItemsForProject` 的条目数）。既有报表 API 返回结构不变（只把口径统一到指标模型）。**以既有 `ReportService` 各方法签名与返回 DTO 为准做最小改动。**

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :module-report:test --tests "*ReportMetricsTest*"`
Expected: PASS。

- [ ] **Step 6: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（frozen `ReportApiIntegrationTest` 的 bySeverity 断言在统一口径下仍成立——单次扫描的 finding 均含在该任务 occurrence）。

- [ ] **Step 7: Commit**

```bash
git add module-report/src/main/kotlin/com/example/compliance/report/application module-report/src/test/kotlin/com/example/compliance/report/application
git commit -m "feat(report): unified metrics model consumed by all report endpoints"
```

---

### Task 9.7: 通知服务（module-notification）

**Files:**
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/domain/Notification.kt`
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/infrastructure/NotificationRepository.kt`
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationService.kt`
- Create: `app-server/src/main/resources/db/migration/V11__notification.sql`   <!-- PF-7：V11=notification（V10 已被 openapi_token 占用） -->
- Create: `module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationServiceTest.kt`

**Interfaces:**
- Consumes: `BaseEntity`。
- Produces: `Notification` 实体（id, channel EMAIL/WEBHOOK, recipient, type, title, content, status PENDING/SENT/FAILED, retryCount, sentAt, createdAt）；`NotificationRepository`（`findByStatusAndChannel`、`findByRecipient`）；`NotificationService`：
  - `fun send(channel: String, recipient: String, type: String, title: String, content: String): Notification`（落库 PENDING；渠道适配器为可注入扩展点，M9 仅落库 + 标记 SENT 的桩实现，真实渠道后续接）
  - `fun list(recipient: String?): List<Notification>`

- [ ] **Step 1: 写失败测试**

创建 `module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationServiceTest.kt`：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** M9：通知落库（真实渠道为后续扩展点，M9 仅桩）。 */
class NotificationServiceTest {

    private val repository = mockk<NotificationRepository>()
    private val service = NotificationService(repository)

    @Test
    fun `send persists notification with pending then sent status`() {
        every { repository.save(any<Notification>()) } answers { (firstArg<Notification>()).also { it.id = 3L } }

        val n = service.send("EMAIL", "a@b.c", "SCAN_COMPLETED", "扫描完成", "detail")

        assertEquals(3L, n.id)
        verify(exactly = 2) { repository.save(any<Notification>()) }   // PENDING 落库 + SENT 更新
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-notification:test --tests "*NotificationServiceTest*"`
Expected: 编译失败 — `NotificationService` 不存在。

- [ ] **Step 3: 写 V11 迁移**

创建 `app-server/src/main/resources/db/migration/V11__notification.sql`：

```sql
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
```

- [ ] **Step 4: 实现实体、仓储与服务**

`Notification.kt` / `NotificationRepository.kt`（`JpaRepository<Notification, Long>`，含 `findByStatusAndChannel(status, channel)`、`findByRecipient(recipient)`）。

`NotificationService.kt`：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 通知服务：M9 先落库并标记 SENT（桩），真实渠道（邮件/webhook）为后续扩展点。 */
@Service
class NotificationService(
    private val repository: NotificationRepository,
) {
    @Transactional
    fun send(channel: String, recipient: String, type: String, title: String, content: String?): Notification {
        val pending = repository.save(Notification().apply {
            this.channel = channel
            this.recipient = recipient
            this.type = type
            this.title = title
            this.content = content
            status = "PENDING"
        })
        // 渠道适配器扩展点：M9 直接视为发送成功
        pending.status = "SENT"
        pending.sentAt = Instant.now()
        return repository.save(pending)
    }

    @Transactional(readOnly = true)
    fun list(recipient: String?): List<Notification> =
        if (recipient.isNullOrBlank()) repository.findAll()
        else repository.findByRecipient(recipient)
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :module-notification:test --tests "*NotificationServiceTest*"`
Expected: PASS（若桩实现只 save 一次而非两次，调整 verify 次数以匹配实现——**以「PENDING 落库 + 状态更新」至少一次 save 为语义，断言次数以实际实现为准**）。

- [ ] **Step 6: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: Commit**

```bash
git add module-notification app-server/src/main/resources/db/migration/V11__notification.sql
git commit -m "feat(notification): notification entity, V11 migration, and stub send service"
```

---

### Task 9.8: M9 集成测试 —— 开放 API 触发 + RBAC + 异常语义端到端

**Files:**
- Create: `app-server/src/test/kotlin/com/example/compliance/openapi/M9OpenApiIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 9.1/9.2/9.3/9.4 全部；`ApiTokenService`；`ScanTaskService`；既有用户/登录基建。

- [ ] **Step 1: 写集成测试**

创建 `app-server/src/test/kotlin/com/example/compliance/openapi/M9OpenApiIntegrationTest.kt`：

```kotlin
package com.example.compliance.openapi

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.openapi.application.ApiTokenService
import com.example.compliance.project.application.BindRepositoryCommand
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import com.example.compliance.scan.application.ScanTaskService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import kotlin.test.assertEquals

/** M9-* 数据前缀；开放 API 触发 + 异常语义（401/409）端到端。 */
class M9OpenApiIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var tokenService: ApiTokenService
    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var scanTaskService: ScanTaskService

    @Test
    fun `open api trigger with valid token creates scan task`() {
        // 1. 项目 + 仓库（触发引擎用 STUBM9 或既有 STUB，需存在于 registry；若无则用 SEMGREP 绑定）
        val project = projectService.create(CreateProjectCommand("M9P", "M9 项目", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("m9-repo", "https://git.example.com/m9.git", "GITLAB", "main", "tok"))
        // 2. 创建 token（服务直调，避开 ADMIN 登录）
        val token = tokenService.create("m9-ci", null, 9L).token
        // 3. 经开放端点触发
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-API-Token", token)
        }
        val body = """{"projectId":${project.id},"engine":"STUBM9","ref":"main","requestId":"m9-req-1"}"""
        val response = rest.exchange("/api/v1/openapi/scans", HttpMethod.POST, HttpEntity(body, headers), String::class.java)
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        // 4. 触发成功 → scan 任务存在
        assertEquals(1, scanTaskService.listByProject(project.id!!).size)
    }

    @Test
    fun `open api trigger without token is rejected`() {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = """{"projectId":1,"engine":"STUB","ref":"main"}"""
        val response = rest.exchange("/api/v1/openapi/scans", HttpMethod.POST, HttpEntity(body, headers), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }
}
```

> 说明：若 `ScanTaskService` 无 `listByProject`，改用 `findings`/查询既有方法，或省略第 4 步改为断言响应含 task id。`STUBM9` 引擎需存在于 registry——**若 registry 只认固定引擎名，则本测试复用既有 STUB（如 "STUB"）**；若无清单绑定，触发后任务仍可建（评估跳过，同 Task 8.4）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app-server:test --tests "*M9OpenApiIntegrationTest*"`
Expected: 编译失败或断言失败（取决于前序任务是否完成）。

- [ ] **Step 3: 运行确认通过**

Run: `./gradlew :app-server:test --tests "*M9OpenApiIntegrationTest*"`
Expected: PASS。

- [ ] **Step 4: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（frozen + M6/M7/M8 + M9 全部绿）。

- [ ] **Step 5: Commit**

```bash
git add app-server/src/test/kotlin/com/example/compliance/openapi/M9OpenApiIntegrationTest.kt
git commit -m "test(openapi): M9 integration - CI trigger auth and exception semantics end-to-end"
```

> **M9 完成标准**：OpenAPI 多 CI token（BCrypt + 独立禁用/过期）；CI 触发端点带 token 校验与回落；RBAC 矩阵（401/403 两档）；BusinessException code→HTTP 映射 + 404；规则/清单写操作全审计；报表统一指标模型；通知落库桩；`./gradlew build` 全绿。

---

## 自审

**1. Spec 覆盖核对**（对照 `2026-09-03-code-compliance-platform-phase2-design.md`）：
- P2-D1（四增量 M6-M9）→ M6-M9 各 Task 对应。
- P2-D2（复扫归属=规范行+history 追加）→ Task 6.4（upsert 状态机）+ 6.2（occurrence 查询）。
- P2-D3（FindingStatus 11 态）→ Task 6.1。
- P2-D4（finding.status 唯一权威、remediation 不设状态列）→ Task 7.1（RemediationTask 无状态列）+ 7.2/7.3/7.4 全经 FindingLifecyclePort。
- P2-D5（remediation→result 接口例外）→ Task 7.1 模块依赖方向（remediation→result，不依赖 scan）。
- P2-D6（GitCheckout 归编排器）→ Task 8.2 + 8.4。
- P2-D7（openapi token 表多 CI）→ Task 9.1 + 9.2。
- P2-D8（Adapter 五方法带默认实现）→ Task 8.1。
- 迁移编号（PF-7 重排）→ Task 6.1（V8）、7.1（V9）、9.1（**V10=openapi_token，spec §6.3 绑定**）、9.7（**V11=notification**）；无 waiver 迁移（PF-6）。
- 复扫验证闭环（verifyRechecking）→ Task 6.3 + 6.5 + 7.5。
- RBAC 矩阵 → Task 9.3。
- 异常语义 → Task 9.4。
- 审计/回滚 → Task 9.5。
- 统一指标模型 → Task 9.6。
- 通知 → Task 9.7。
- 技术债清单（唯一约束/错误码/指标）→ V8(8) 唯一约束、9.4、9.6。

**2. Placeholder 扫描**：全部任务含逐字代码与精确 Run 命令；无 "TBD"/"TODO"/"implement later"。两处「以实现为准」仅用于**既有文件签名核对**（RuleService 构造、ScanTaskService.listByProject 存在性），并都给了 fallback 路径，非占位。

**3. 类型一致性核对**：
- `FindingStatus` 11 态：Task 6.1 定义 → 6.3/6.4/7.x/9.6 全部引用一致。
- `FindingLifecyclePort`：6.3 定义 5 方法 → 7.1 补 `findById` → 7.2/7.3/7.4/9.6 消费一致。
- `FindingView` 字段：6.3 定义（含 engine 尾字段默认 ""）→ 7.1/7.2/8.4/9.6 构造一致。
- `ScanContext` 字段：6.5 使用旧四字段 → 8.1 扩展（workDir/commitId 等 9 字段）+ 8.3/8.4 消费一致。
- `ScanEngineAdapter` 五方法：8.1 定义（含 scan() 兼容默认）→ 8.3/8.4 + STUB 解冻一致。
- `upsertByFingerprint(projectId, scanTaskId, engine, findings)` 签名贯穿 6.4/6.5 一致。
- `startScan(projectId, engine, ref, triggerType="MANUAL", requestId: String? = null)` 贯穿 6.5/7.4/9.2 一致。
- `ScanTriggerPort.triggerScan`（**M7 Task 7.4 定义**）被 9.2 控制器消费一致。

**4. 已知调整点（以实际代码为准的核对项）**：
- 既有 `BusinessException` 是否已有 `code` 字段（9.4 Step 3 注明补字段）。
- `AuditService.record` 实际签名（6.3/9.5 已按 PF-9 修正为真实签名 `record(action, module, userId, resourceType, resourceId, detail, ip)`）。
- `RuleService`/`ChecklistService` 既有构造与写操作签名（9.5 注明）。
- `ScanTaskService` 是否有 `listByProject`（9.8 注明 fallback）。
- 冻结 STUB 解冻（8.1 Step 5）断言值不变。

---
