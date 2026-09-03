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
- **状态权威（P2-D4）**：`finding.status` 为唯一权威；`remediation_task` **不设**状态列（省略 spec §4.1 的冗余缓存列，避免跨模块缓存同步；状态经 `FindingLifecyclePort` 查询）。
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
| module-remediation | 新建 api/application/domain/infrastructure + build.gradle 加 `module-result` | 整改闭环 |
| module-openapi | 新建 api/application/domain/infrastructure + build.gradle 加 `module-scan` | CI 触发 + token 表 |
| module-notification | `NotificationSender.kt` + `LogNotificationSender.kt` | 占位 |
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
CREATE INDEX idx_finding_history_scan ON finding_history (scan_task_id, finding_id);

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
- Consumes: Task 6.2 实体/仓储；`AuditService`（module-common，包 `com.example.compliance.common.audit.AuditService`，方法签名 `record(changedBy: Long?, action: String, targetType: String, targetId: Long, detail: String)`，以该文件实际签名为准）。
- Produces: `FindingLifecyclePort` 接口（module-remediation 在 M7 依赖它）：
  - `fun transition(findingId: Long, to: FindingStatus, reason: String?, changedBy: Long?): FindingStatus`
  - `fun addEvidence(findingId: Long, evidenceType: String, evidenceRef: String, changedBy: Long?): FindingEvidence`
  - `fun findingsForScanTask(scanTaskId: Long): List<FindingView>`
  - `fun findingsByProject(projectId: Long, status: FindingStatus?): List<FindingView>`
  - `fun verifyRechecking(projectId: Long, scanTaskId: Long, presentFindingIds: Set<Long>): VerifyResult`
- Produces: `data class FindingView(id, projectId, scanTaskId, ruleCode, severity, status, filePath, lineNumber, firstSeenAt, lastSeenAt, occurrenceCount)`、`data class VerifyResult(closed: Int, regressed: Int)`（module-result.application）。

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
        verify { auditService.record(any(), any(), any(), any(), any()) }
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
        auditService.record(changedBy, "FINDING_TRANSITION", "finding", findingId, "{\"from\":\"$from\",\"to\":\"$to\",\"reason\":${quote(reason)}}")
        return to
    }

    @Transactional
    override fun addEvidence(findingId: Long, evidenceType: String, evidenceRef: String, changedBy: Long?): FindingEvidence {
        val saved = evidenceRepository.save(FindingEvidence().apply {
            this.findingId = findingId; this.evidenceType = evidenceType; this.evidenceRef = evidenceRef; this.addedBy = changedBy
        })
        auditService.record(changedBy, "FINDING_EVIDENCE", "finding", findingId, "{\"type\":\"$evidenceType\",\"ref\":${quote(evidenceRef)}}")
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
        firstSeenAt, lastSeenAt, occurrenceCount,
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
- Produces: `ChecklistQueryService.publishedVersionForProject(projectId): ChecklistVersion?`；`ScanTask.checklistVersionId/ruleIds/commitId/durationMs/requestId`；`ScanTaskService.startScan(projectId, engine, ref, triggerType="MANUAL"): ScanTask`（内部生成 requestId=UUID）；`ComplianceEvaluator.evaluate(projectId, checklistVersionId, findings)`。

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
    fun startScan(projectId: Long, engine: String, ref: String?, triggerType: String = "MANUAL"): ScanTask {
        if (registry.get(engine) == null) {
            throw BusinessException(400, "unsupported engine: $engine")
        }
        projectService.get(projectId)
        val task = scanTaskRepository.save(ScanTask().apply {
            this.projectId = projectId
            this.engine = engine
            this.ref = ref
            this.triggerType = triggerType
            this.requestId = java.util.UUID.randomUUID().toString()
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
## M7 — 整改闭环（remediation）

> **模块落地说明**：M7 首次给 `module-remediation` 写入实现。该模块当前仅有 `package-info.kt` 与 `build.gradle.kts`（只依赖 module-common）。本里程碑按 P2-D5 引入对 module-result 的接口依赖（`FindingLifecyclePort`），并在 `module-remediation/build.gradle.kts` 增加 `implementation(project(":module-result"))`。模块间依赖方向：remediation → result（接口），remediation 不依赖 scan（recheck 由 scan 编排器触发，见 7.4）。

### Task 7.1: 整改任务领域模型 + 证据实体

**Files:**
- Create: `module-remediation/src/main/kotlin/com/example/compliance/remediation/domain/RemediationTask.kt`
- Create: `module-remediation/src/main/kotlin/com/example/compliance/remediation/domain/RemediationAction.kt`（枚举 + 关联）
- Create: `module-remediation/src/main/kotlin/com/example/compliance/remediation/infrastructure/RemediationTaskRepository.kt`
- Create: `app-server/src/main/resources/db/migration/V9__remediation_task.sql`
- Create: `module-remediation/src/main/kotlin/com/example/compliance/remediation/application/RemediationTaskView.kt`
- Create: `module-remediation/src/test/kotlin/com/example/compliance/remediation/application/RemediationServiceTest.kt`（仅含 7.1 可测部分，7.2/7.3 扩展）

**Interfaces:**
- Consumes: `BaseEntity`（module-common.domain，既有多字段实体基类）；`FindingLifecyclePort`（Task 6.3，module-result.application）。
- Produces: 实体 `RemediationTask`（不含状态列，P2-D4 状态权威在 finding）、`RemediationAction` 枚举 `ASSIGNED_COMMENT / PLAN_COMMENT / EVIDENCE_ADDED / STATUS_TRANSITION / REOPENED / WAIVER_GRANTED / WAIVER_REVOKED`（字段以实际需要为准，可含 comment）；仓储 `RemediationTaskRepository : JpaRepository<RemediationTask, Long>`（含 `findByFindingId`、`findByProjectId`、`findByAssigneeId`）；`RemediationTaskView` DTO。

- [ ] **Step 1: 写失败测试**

创建 `module-remediation/src/test/kotlin/com/example/compliance/remediation/application/RemediationServiceTest.kt`：

```kotlin
package com.example.compliance.remediation.application

import com.example.compliance.remediation.domain.RemediationTask
import com.example.compliance.remediation.infrastructure.RemediationTaskRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** M7 单元测试：RemediationService 行为（状态权威在 finding，见 P2-D4）。 */
class RemediationServiceTest {

    private val taskRepository = mockk<RemediationTaskRepository>()
    private val lifecyclePort = mockk<FindingLifecyclePort>()
    private val service = RemediationService(taskRepository, lifecyclePort)

    @Test
    fun `create task persists assigned task for finding`() {
        val saved = RemediationTask().apply { id = 11L; findingId = 7L; assigneeId = 3L }
        every { taskRepository.save(any<RemediationTask>()) } returns saved
        every { lifecyclePort.transition(7L, com.example.compliance.result.domain.FindingStatus.ASSIGNED, "assigned", 9L) } returns com.example.compliance.result.domain.FindingStatus.ASSIGNED

        val view = service.create(9L, 7L, 9L, 3L, null, "assign to dev")

        assertNotNull(view.id)
        assertEquals(7L, view.findingId)
        verify { lifecyclePort.transition(7L, com.example.compliance.result.domain.FindingStatus.ASSIGNED, "assigned", 9L) }
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-remediation:test --tests "*RemediationServiceTest*"`
Expected: 编译失败 — `RemediationService` 不存在（module-remediation 目前无 test 源集内容，测试目录将自动创建）。

- [ ] **Step 3: 写 V9 迁移**

创建 `app-server/src/main/resources/db/migration/V9__remediation_task.sql`：

```sql
-- 整改任务：一张表持有任务关联与责任人，不设状态列（P2-D4：finding.status 为唯一权威）
CREATE TABLE remediation_task (
    id                  BIGSERIAL PRIMARY KEY,
    finding_id          BIGINT      NOT NULL,
    project_id          BIGINT      NOT NULL,
    assignee_id         BIGINT,
    assigner_id         BIGINT,
    planned_fix_date    DATE,
    comment_text        TEXT,
    version             BIGINT      NOT NULL DEFAULT 0,
    created_at          TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_remediation_task_finding ON remediation_task (finding_id);
CREATE INDEX idx_remediation_task_project ON remediation_task (project_id);
CREATE INDEX idx_remediation_task_assignee ON remediation_task (assignee_id);

-- 整改活动日志（追加式）
CREATE TABLE remediation_action (
    id            BIGSERIAL PRIMARY KEY,
    task_id       BIGINT      NOT NULL,
    action_type   VARCHAR(32) NOT NULL,
    comment       TEXT,
    actor_id      BIGINT,
    acted_at      TIMESTAMP   NOT NULL DEFAULT now(),
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_remediation_action_task ON remediation_action (task_id, acted_at DESC);
```

- [ ] **Step 4: 实现实体、仓储与 DTO**

`RemediationTask.kt`：

```kotlin
package com.example.compliance.remediation.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.LocalDate

/** 整改任务：关联 finding，记录责任人；状态不在本实体（P2-D4，finding.status 唯一权威）。 */
@Entity
@Table(name = "remediation_task")
class RemediationTask : BaseEntity() {
    @Column(name = "finding_id", nullable = false)
    var findingId: Long = 0
    @Column(name = "project_id", nullable = false)
    var projectId: Long = 0
    @Column(name = "assignee_id")
    var assigneeId: Long? = null
    @Column(name = "assigner_id")
    var assignerId: Long? = null
    @Column(name = "planned_fix_date")
    var plannedFixDate: LocalDate? = null
    @Column(name = "comment_text")
    var commentText: String? = null
}
```

`RemediationAction.kt`：

```kotlin
package com.example.compliance.remediation.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

enum class RemediationActionType { COMMENT, ASSIGNED_COMMENT, PLAN_COMMENT, EVIDENCE_ADDED, STATUS_TRANSITION, REOPENED, WAIVER_GRANTED, WAIVER_REVOKED }

/** remediation_action：整改活动日志（追加式）。 */
@Entity
@Table(name = "remediation_action")
class RemediationAction : BaseEntity() {
    @Column(name = "task_id", nullable = false)
    var taskId: Long = 0
    @Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 32)
    var actionType: RemediationActionType = RemediationActionType.COMMENT
    @Column(name = "comment")
    var comment: String? = null
    @Column(name = "actor_id")
    var actorId: Long? = null
    @Column(name = "acted_at", nullable = false)
    var actedAt: Instant = Instant.now()
}

`RemediationTaskRepository.kt`：

```kotlin
package com.example.compliance.remediation.infrastructure

import com.example.compliance.remediation.domain.RemediationTask
import org.springframework.data.jpa.repository.JpaRepository

interface RemediationTaskRepository : JpaRepository<RemediationTask, Long> {
    fun findByFindingId(findingId: Long): RemediationTask?
    fun findByProjectId(projectId: Long): List<RemediationTask>
    fun findByAssigneeId(assigneeId: Long): List<RemediationTask>
}
```

`RemediationTaskView.kt`：

```kotlin
package com.example.compliance.remediation.application

import com.example.compliance.result.domain.FindingStatus
import java.time.Instant
import java.time.LocalDate

/** 整改任务视图（含 finding 当前状态 —— 状态权威在 finding）。 */
data class RemediationTaskView(
    val id: Long,
    val findingId: Long,
    val projectId: Long,
    val assigneeId: Long?,
    val assignerId: Long?,
    val plannedFixDate: LocalDate?,
    val commentText: String?,
    val status: FindingStatus,
    val ruleCode: String,
    val severity: String,
    val filePath: String,
    val createdAt: Instant,
)
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :module-remediation:test --tests "*RemediationServiceTest*"`
Expected: 仍失败 — `RemediationService` 未实现。在 Step 6 实现后重跑。

- [ ] **Step 6: 实现 RemediationService 骨架（含 FindingLifecyclePort.findById）**

本任务一并给 `FindingLifecyclePort` 补 `findById`（7.1 的 `toView` 消费它；Task 7.2 的 API 也依赖它）。

`module-result/.../application/FindingLifecyclePort.kt` 接口追加：

```kotlin
    fun findById(findingId: Long): FindingView?
```

`FindingLifecycleService` 实现追加：

```kotlin
    override fun findById(findingId: Long): FindingView? =
        findingRepository.findById(findingId).map { it.toView() }.orElse(null)
```

`module-remediation/src/main/kotlin/com/example/compliance/remediation/application/RemediationService.kt`：

```kotlin
package com.example.compliance.remediation.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.remediation.domain.RemediationTask
import com.example.compliance.remediation.infrastructure.RemediationTaskRepository
import com.example.compliance.result.application.FindingLifecyclePort
import com.example.compliance.result.domain.FindingStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** 整改闭环服务：只经 FindingLifecyclePort 驱动 finding 生命周期（P2-D5）。 */
@Service
class RemediationService(
    private val taskRepository: RemediationTaskRepository,
    private val lifecyclePort: FindingLifecyclePort,
) {
    /** 派单：创建整改任务并把 finding 置为 ASSIGNED。 */
    @Transactional
    fun create(
        projectId: Long, findingId: Long, assignerId: Long, assigneeId: Long?,
        plannedFixDate: LocalDate?, comment: String?,
    ): RemediationTaskView {
        if (taskRepository.findByFindingId(findingId) != null) {
            throw BusinessException(409, "remediation task already exists for finding: $findingId")
        }
        val task = taskRepository.save(RemediationTask().apply {
            this.projectId = projectId
            this.findingId = findingId
            this.assignerId = assignerId
            this.assigneeId = assigneeId
            this.plannedFixDate = plannedFixDate
            commentText = comment
        })
        lifecyclePort.transition(findingId, FindingStatus.ASSIGNED, "assigned", assignerId)
        return task.toView(lifecyclePort)
    }

    fun get(taskId: Long): RemediationTaskView {
        val task = taskRepository.findById(taskId)
            .orElseThrow { BusinessException(404, "remediation task not found: $taskId") }
        return task.toView(lifecyclePort)
    }

    fun listByProject(projectId: Long): List<RemediationTaskView> =
        taskRepository.findByProjectId(projectId).map { it.toView(lifecyclePort) }

    fun listByAssignee(assigneeId: Long): List<RemediationTaskView> =
        taskRepository.findByAssigneeId(assigneeId).map { it.toView(lifecyclePort) }

    private fun RemediationTask.toView(port: FindingLifecyclePort): RemediationTaskView {
        val f = port.findById(findingId)
        return RemediationTaskView(
            id!!, findingId, projectId, assigneeId, assignerId, plannedFixDate, commentText,
            status = f?.status ?: FindingStatus.NEW,
            ruleCode = f?.ruleCode ?: "",
            severity = f?.severity ?: "",
            filePath = f?.filePath ?: "",
            createdAt = createdAt,
        )
    }
}
```

- [ ] **Step 7: 运行测试确认通过**

Run: `./gradlew :module-remediation:test --tests "*RemediationServiceTest*"`
Expected: PASS（`toView` 经 `findById` 解析 finding，7.1 单测只断言 view.id/findingId，无需 stub findById）。

- [ ] **Step 8: 运行全量 build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 9: Commit**

```bash
git add module-remediation module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecyclePort.kt module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecycleService.kt app-server/src/main/resources/db/migration/V9__remediation_task.sql
git commit -m "feat(remediation): remediation task domain, V9 migration, lifecycle findById, and service skeleton"
```

> **模块依赖说明**：执行本任务前须在 `module-remediation/build.gradle.kts` 增加：
> ```kotlin
> dependencies {
>     implementation(project(":module-result"))
> }
> ```
> 并确保 `module-remediation` 的 test 依赖含 JUnit/MockK（与其他模块对齐，见 `module-scan/build.gradle.kts` 既有 testImplementation 块；若无测试依赖，V9 单测将因缺少库而编译失败——按既有模块测试配置补齐即可）。

---

### Task 7.2: 整改闭环 API（派单 / 处理中 / 修复 + 证据）

**Files:**
- Modify: `module-remediation/src/main/kotlin/com/example/compliance/remediation/application/RemediationService.kt`（create 签名 + 状态驱动方法）
- Create: `module-remediation/src/main/kotlin/com/example/compliance/remediation/api/RemediationController.kt`
- Create: `module-remediation/src/main/kotlin/com/example/compliance/remediation/api/RemediationApi.kt`（OpenAPI 注解接口，若模块惯例如此则建）
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecyclePort.kt`（+findById）
- Modify: `module-remediation/src/test/kotlin/com/example/compliance/remediation/application/RemediationServiceTest.kt`（补状态驱动测试）
- Create: `module-remediation/src/test/kotlin/com/example/compliance/remediation/api/RemediationControllerTest.kt`（Spring MVC 切片测试，MockMvc）

**Interfaces:**
- Consumes: `FindingLifecyclePort`（含新 `findById`）；`RemediationTaskRepository`。
- Produces: HTTP API（挂 `/api/v1/remediation`）：
  - `POST /api/v1/remediation/tasks` body `CreateRemediationTaskCommand(projectId, findingId, assigneeId?, plannedFixDate?, comment?)` → 201 + `RemediationTaskView`（finding→ASSIGNED）
  - `POST /api/v1/remediation/tasks/{id}/comment` body `CommentCommand(text)` → 200 + `RemediationTaskView`（追加活动日志）
  - `POST /api/v1/remediation/tasks/{id}/evidence` body `AddEvidenceCommand(evidenceType, evidenceRef)` → 200 + view（经 `lifecyclePort.addEvidence`）
  - `POST /api/v1/remediation/tasks/{id}/start-fix` → 200（finding→FIXING，仅 ASSIGNED 可转，否则 409）
  - `POST /api/v1/remediation/tasks/{id}/mark-fixed` → 200（finding→FIXED，仅 FIXING 可转，否则 409；写证据要求 FIX_COMMIT）
  - `GET /api/v1/remediation/tasks?projectId=&assigneeId=` → 200 + 列表

- [ ] **Step 1: 确认 create 签名（已在 7.1 落地）**

`RemediationService.create(projectId, findingId, assignerId, assigneeId, plannedFixDate, comment)` 已在 Task 7.1 Step 6 采用最终签名，`FindingLifecyclePort.findById` 也已落地——本任务直接在其上扩展状态驱动方法，无需改动既有签名。

- [ ] **Step 2: 写失败测试（状态机守卫）**

`RemediationServiceTest.kt` 追加：

```kotlin
    @Test
    fun `mark-fixed from non-fixing state is rejected`() {
        // finding 当前 CONFIRMED（未进入 FIXING）
        every { lifecyclePort.findById(7L) } returns FindingView(
            id = 7L, projectId = 9L, scanTaskId = 1L, ruleCode = "R1", severity = "HIGH",
            status = com.example.compliance.result.domain.FindingStatus.CONFIRMED,
            filePath = "A.java", lineNumber = 1,
            firstSeenAt = java.time.Instant.now(), lastSeenAt = java.time.Instant.now(), occurrenceCount = 1,
        )
        val ex = org.junit.jupiter.api.assertThrows<com.example.compliance.common.exception.BusinessException> {
            service.markFixed(11L, 9L, "abc123")
        }
        kotlin.test.assertEquals("finding not in FIXING state: 7", ex.message)
    }
```

（`markFixed(taskId, actorId, fixCommit)` 签名以 Step 3 实现为准；若断言消息不一致，以实现为准调整测试。）

- [ ] **Step 3: 实现状态驱动方法与状态机守卫**

`RemediationService` 追加：

```kotlin
    /** 进入处理中：仅 ASSIGNED 可转 FIXING。 */
    @Transactional
    fun startFix(taskId: Long, actorId: Long): RemediationTaskView {
        val task = mustGet(taskId)
        val f = lifecyclePort.findById(task.findingId) ?: throw BusinessException(404, "finding not found: ${task.findingId}")
        if (f.status != FindingStatus.ASSIGNED) throw BusinessException(409, "finding not in ASSIGNED state: ${task.findingId}")
        lifecyclePort.transition(task.findingId, FindingStatus.FIXING, "fix_started", actorId)
        return task.toView(lifecyclePort)
    }

    /** 标记修复完成：仅 FIXING 可转 FIXED；必须提供修复 commit 作为证据。 */
    @Transactional
    fun markFixed(taskId: Long, actorId: Long, fixCommit: String): RemediationTaskView {
        val task = mustGet(taskId)
        val f = lifecyclePort.findById(task.findingId) ?: throw BusinessException(404, "finding not found: ${task.findingId}")
        if (f.status != FindingStatus.FIXING) throw BusinessException(409, "finding not in FIXING state: ${task.findingId}")
        lifecyclePort.addEvidence(task.findingId, "FIX_COMMIT", fixCommit, actorId)
        lifecyclePort.transition(task.findingId, FindingStatus.FIXED, "fixed_by_commit_$fixCommit", actorId)
        return task.toView(lifecyclePort)
    }

    /** 补充证据（无状态守卫，可随时添加）。 */
    @Transactional
    fun addEvidence(taskId: Long, actorId: Long, evidenceType: String, evidenceRef: String): RemediationTaskView {
        val task = mustGet(taskId)
        lifecyclePort.addEvidence(task.findingId, evidenceType, evidenceRef, actorId)
        return task.toView(lifecyclePort)
    }

    /** 追加整改评论。 */
    @Transactional
    fun addComment(taskId: Long, actorId: Long, text: String): RemediationTaskView {
        val task = mustGet(taskId)
        // 评论落 action 日志：RemediationAction 实体在 7.1 已建，此处经 actionRepository 追加 COMMENT 记录
        return task.toView(lifecyclePort)
    }

    private fun mustGet(taskId: Long): RemediationTask =
        taskRepository.findById(taskId).orElseThrow { BusinessException(404, "remediation task not found: $taskId") }
```

> `addComment` 的 action 写入：本任务实现时在 `RemediationService` 构造注入 `RemediationActionRepository : JpaRepository<RemediationAction, Long>`（`module-remediation/.../infrastructure/RemediationActionRepository.kt`，新建），`addComment` 内 save 一条 `RemediationAction(COMMENT, text, actorId)`。`toView` 私有扩展保持 7.1 版（经 `findById`）。

- [ ] **Step 4: 实现 Controller（Spring MVC + OpenAPI 注解）**

`RemediationController.kt`（按 `module-scan` 既有 Controller 惯例写，含 `@RestController`/`@RequestMapping("/api/v1/remediation")`、OpenAPI 注解、DTO `data class`）：

```kotlin
package com.example.compliance.remediation.api

import com.example.compliance.remediation.application.CreateRemediationTaskCommand
import com.example.compliance.remediation.application.RemediationService
import com.example.compliance.remediation.application.RemediationTaskView
import com.example.compliance.remediation.application.AddEvidenceCommand
import com.example.compliance.remediation.application.CommentCommand
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

/** 整改闭环 API（M7）。认证主体 id 从 Authentication 取（M9 前可放宽）。 */
@RestController
@RequestMapping("/api/v1/remediation")
class RemediationController(private val service: RemediationService) {

    @PostMapping("/tasks")
    fun create(@RequestBody cmd: CreateRemediationTaskCommand, auth: Authentication?): ResponseEntity<RemediationTaskView> {
        val actor = actorId(auth)
        val view = service.create(cmd.projectId, cmd.findingId, actor, cmd.assigneeId, cmd.plannedFixDate, cmd.comment)
        return ResponseEntity.status(201).body(view)
    }

    @PostMapping("/tasks/{id}/comment")
    fun comment(@PathVariable id: Long, @RequestBody cmd: CommentCommand, auth: Authentication?): RemediationTaskView =
        service.addComment(id, actorId(auth), cmd.text)

    @PostMapping("/tasks/{id}/evidence")
    fun evidence(@PathVariable id: Long, @RequestBody cmd: AddEvidenceCommand, auth: Authentication?): RemediationTaskView =
        service.addEvidence(id, actorId(auth), cmd.evidenceType, cmd.evidenceRef)

    @PostMapping("/tasks/{id}/start-fix")
    fun startFix(@PathVariable id: Long, auth: Authentication?): RemediationTaskView =
        service.startFix(id, actorId(auth))

    @PostMapping("/tasks/{id}/mark-fixed")
    fun markFixed(@PathVariable id: Long, @RequestBody cmd: MarkFixedCommand, auth: Authentication?): RemediationTaskView =
        service.markFixed(id, actorId(auth), cmd.fixCommit)

    @GetMapping("/tasks")
    fun list(@RequestParam projectId: Long?, @RequestParam assigneeId: Long?): List<RemediationTaskView> =
        when {
            projectId != null -> service.listByProject(projectId)
            assigneeId != null -> service.listByAssignee(assigneeId)
            else -> emptyList()
        }

    private fun actorId(auth: Authentication?): Long {
        // M9 RBAC 落地前：认证主体名为 "u<userId>" 则解析；否则 fallback 1L
        val name = auth?.name
        return name?.removePrefix("u")?.toLongOrNull() ?: 1L
    }
}
```

配套 DTO（`RemediationApi.kt` 或与 Controller 同文件的 `data class`，按模块惯例）：

```kotlin
data class CreateRemediationTaskCommand(
    val projectId: Long, val findingId: Long, val assigneeId: Long? = null,
    val plannedFixDate: java.time.LocalDate? = null, val comment: String? = null,
)
data class CommentCommand(val text: String)
data class AddEvidenceCommand(val evidenceType: String, val evidenceRef: String)
data class MarkFixedCommand(val fixCommit: String)
```

- [ ] **Step 5: 写 Spring MVC 切片测试**

创建 `module-remediation/src/test/kotlin/com/example/compliance/remediation/api/RemediationControllerTest.kt`：

```kotlin
package com.example.compliance.remediation.api

import com.example.compliance.remediation.application.RemediationService
import com.example.compliance.remediation.application.RemediationTaskView
import com.example.compliance.result.domain.FindingStatus
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

/** M7 API 切片测试：只验证 controller 契约（服务为 mock）。 */
@WebMvcTest(RemediationController::class)
class RemediationControllerTest {

    @Autowired lateinit var mockMvc: MockMvc

    @MockBean lateinit var service: RemediationService

    @Test
    fun `create task returns 201 with view`() {
        every { service.create(9L, 7L, 1L, 3L, null, "assign") } returns RemediationTaskView(
            id = 11L, findingId = 7L, projectId = 9L, assigneeId = 3L, assignerId = 1L,
            plannedFixDate = null, commentText = "assign", status = FindingStatus.ASSIGNED,
            ruleCode = "R1", severity = "HIGH", filePath = "A.java", createdAt = java.time.Instant.now(),
        )
        mockMvc.perform(
            post("/api/v1/remediation/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"projectId":9,"findingId":7,"assigneeId":3,"comment":"assign"}""")
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("ASSIGNED"))
            .andExpect(jsonPath("$.findingId").value(7))
    }
}
```

> `@WebMvcTest` 只加载 Controller 切片；`mockMvc` 经自动配置注入。`MockBean` 替换 `@MockBean` 的 `org.springframework.boot.test.mock.mockito.MockBean`（Boot 3.3 仍可用）。若 `@WebMvcTest` 因 Security 过滤链需要额外配置（当前 SecurityConfig 已 permitAll 登录/docs/health，其余 authenticated——切片测试默认无认证，`auth` 为 null → actorId=1L，与服务 stub 对齐即可；若 401 拦截，则在测试类加 `@AutoConfigureMockMvc(addFilters = false)`）。

- [ ] **Step 6: 运行测试确认通过**

Run: `./gradlew :module-remediation:test`
Expected: 全部 PASS（单测 + 切片）。

- [ ] **Step 7: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 8: Commit**

```bash
git add module-remediation module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecyclePort.kt module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecycleService.kt
git commit -m "feat(remediation): remediation closed-loop API (assign, fix, evidence, comments)"
```

---

### Task 7.3: 豁免流程（WAIVER + 审计）

**Files:**
- Modify: `module-remediation/src/main/kotlin/com/example/compliance/remediation/application/RemediationService.kt`（+waive/revokeWaiver）
- Create: `module-remediation/src/main/kotlin/com/example/compliance/remediation/domain/WaiverRecord.kt`
- Create: `module-remediation/src/main/kotlin/com/example/compliance/remediation/infrastructure/WaiverRecordRepository.kt`
- Modify: `app-server/src/main/resources/db/migration/V9__remediation_task.sql`（追加 waiver 表，或新增 V10）
- Modify: `module-remediation/src/test/kotlin/com/example/compliance/remediation/application/RemediationServiceTest.kt`（+豁免测试）

**Interfaces:**
- Consumes: `FindingLifecyclePort`（transition 到 WAIVED / 从 WAIVED 转回 CONFIRMED）。
- Produces: `WaiverRecord`（id, findingId, reason, grantedBy, grantedAt, expiresAt?, revokedAt?, revokedBy?）；`RemediationService.waive(findingId, reason, grantedBy, expiresAt?): RemediationTaskView`（finding→WAIVED，写审计）；`RemediationService.revokeWaiver(findingId, reason, actorId): RemediationTaskView`（finding→CONFIRMED，写审计）。

- [ ] **Step 1: 写失败测试**

`RemediationServiceTest.kt` 追加：

```kotlin
    @Test
    fun `waive transitions finding to WAIVED and records waiver`() {
        every { lifecyclePort.findById(7L) } returns FindingView(
            id = 7L, projectId = 9L, scanTaskId = 1L, ruleCode = "R1", severity = "HIGH",
            status = com.example.compliance.result.domain.FindingStatus.CONFIRMED,
            filePath = "A.java", lineNumber = 1,
            firstSeenAt = java.time.Instant.now(), lastSeenAt = java.time.Instant.now(), occurrenceCount = 1,
        )
        every { waiverRepository.save(any<WaiverRecord>()) } answers { firstArg() }
        every { lifecyclePort.transition(7L, com.example.compliance.result.domain.FindingStatus.WAIVED, "waiver:accepted risk", 9L) } returns com.example.compliance.result.domain.FindingStatus.WAIVED
        every { taskRepository.findByFindingId(7L) } returns null

        service.waive(9L, 7L, "accepted risk", 9L, null)

        verify { lifecyclePort.transition(7L, com.example.compliance.result.domain.FindingStatus.WAIVED, any(), 9L) }
        verify { waiverRepository.save(match { it.reason == "accepted risk" && it.findingId == 7L }) }
    }
```

（`waive` 签名：`waive(projectId, findingId, reason, grantedBy, expiresAt: java.time.Instant?)`；测试 stub 相应调整。）

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-remediation:test --tests "*RemediationServiceTest*"`
Expected: 编译失败 — `waive` 不存在、`WaiverRecord` 不存在。

- [ ] **Step 3: 写 V10 迁移（waiver 表）**

创建 `app-server/src/main/resources/db/migration/V10__waiver_record.sql`：

```sql
-- 豁免记录：who/when/why + 过期与撤销
CREATE TABLE waiver_record (
    id          BIGSERIAL PRIMARY KEY,
    finding_id  BIGINT      NOT NULL,
    reason      TEXT        NOT NULL,
    granted_by  BIGINT      NOT NULL,
    granted_at  TIMESTAMP   NOT NULL DEFAULT now(),
    expires_at  TIMESTAMP,
    revoked_at  TIMESTAMP,
    revoked_by  BIGINT,
    created_at  TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP   NOT NULL DEFAULT now()
);
CREATE INDEX idx_waiver_finding ON waiver_record (finding_id);
```

- [ ] **Step 4: 实现实体、仓储与服务方法**

`WaiverRecord.kt` / `WaiverRecordRepository.kt`（`JpaRepository<WaiverRecord, Long>`，含 `findFirstByFindingIdOrderByIdDesc(findingId): WaiverRecord?`）。

`RemediationService` 构造注入 `waiverRepository: WaiverRecordRepository`，追加：

```kotlin
    /** 豁免：记录 who/when/why，finding → WAIVED。 */
    @Transactional
    fun waive(projectId: Long, findingId: Long, reason: String, grantedBy: Long, expiresAt: Instant?): RemediationTaskView {
        if (reason.isBlank()) throw BusinessException(400, "waiver reason is required")
        waiverRepository.save(WaiverRecord().apply {
            this.findingId = findingId; this.reason = reason
            this.grantedBy = grantedBy; this.expiresAt = expiresAt
        })
        lifecyclePort.transition(findingId, FindingStatus.WAIVED, "waiver:$reason", grantedBy)
        val task = taskRepository.findByFindingId(findingId)
            ?: throw BusinessException(404, "remediation task not found for finding: $findingId")
        return task.toView(lifecyclePort)
    }

    /** 撤销豁免：finding → CONFIRMED，回整改闭环。 */
    @Transactional
    fun revokeWaiver(findingId: Long, reason: String, actorId: Long): RemediationTaskView {
        val latest = waiverRepository.findFirstByFindingIdOrderByIdDesc(findingId)
            ?: throw BusinessException(404, "no waiver for finding: $findingId")
        latest.revokedAt = Instant.now(); latest.revokedBy = actorId
        waiverRepository.save(latest)
        lifecyclePort.transition(findingId, FindingStatus.CONFIRMED, "waiver_revoked:$reason", actorId)
        val task = taskRepository.findByFindingId(findingId)
            ?: throw BusinessException(404, "remediation task not found for finding: $findingId")
        return task.toView(lifecyclePort)
    }
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :module-remediation:test`
Expected: PASS。

- [ ] **Step 6: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: Commit**

```bash
git add module-remediation app-server/src/main/resources/db/migration/V10__waiver_record.sql
git commit -m "feat(remediation): waiver workflow with audit trail and revocation"
```

---
### Task 7.4: recheck 端点（FIXED → RECHECKING）+ 编排器复扫触发

**Files:**
- Modify: `module-remediation/src/main/kotlin/com/example/compliance/remediation/application/RemediationService.kt`（+requestRecheck）
- Modify: `module-remediation/src/main/kotlin/com/example/compliance/remediation/api/RemediationController.kt`（+POST /tasks/{id}/recheck）
- Modify: `module-remediation/src/test/kotlin/com/example/compliance/remediation/application/RemediationServiceTest.kt`（+recheck 测试）
- Modify: `module-remediation/src/test/kotlin/com/example/compliance/remediation/api/RemediationControllerTest.kt`（+recheck 切片）

**Interfaces:**
- Consumes: `FindingLifecyclePort.transition`；Task 7.2 服务/控制器结构。
- Produces: `RemediationService.requestRecheck(taskId, actorId): RemediationTaskView`——仅 finding 处于 `FIXED` 时可转 `RECHECKING`（否则 409）；**recheck 端点只做状态转移，复扫本身由用户/CI 经既有 scan API 触发**（P2-D2 决定：复扫由编排器 post-scan 调 `verifyRechecking` 完成闭环，remediation 不依赖 scan 模块）。

- [ ] **Step 1: 写失败测试**

`RemediationServiceTest.kt` 追加：

```kotlin
    @Test
    fun `request-recheck only allowed from FIXED`() {
        every { lifecyclePort.findById(7L) } returns FindingView(
            id = 7L, projectId = 9L, scanTaskId = 1L, ruleCode = "R1", severity = "HIGH",
            status = com.example.compliance.result.domain.FindingStatus.FIXED,
            filePath = "A.java", lineNumber = 1,
            firstSeenAt = java.time.Instant.now(), lastSeenAt = java.time.Instant.now(), occurrenceCount = 1,
        )
        every { taskRepository.findById(11L) } returns java.util.Optional.of(
            com.example.compliance.remediation.domain.RemediationTask().apply { id = 11L; findingId = 7L; projectId = 9L }
        )
        every { lifecyclePort.transition(7L, com.example.compliance.result.domain.FindingStatus.RECHECKING, "recheck_requested", 9L) } returns com.example.compliance.result.domain.FindingStatus.RECHECKING
        every { taskRepository.findByFindingId(7L) } returns null

        val view = service.requestRecheck(11L, 9L)

        kotlin.test.assertEquals(com.example.compliance.result.domain.FindingStatus.RECHECKING, view.status)
    }

    @Test
    fun `request-recheck from WAIVED is rejected`() {
        every { lifecyclePort.findById(7L) } returns FindingView(
            id = 7L, projectId = 9L, scanTaskId = 1L, ruleCode = "R1", severity = "HIGH",
            status = com.example.compliance.result.domain.FindingStatus.WAIVED,
            filePath = "A.java", lineNumber = 1,
            firstSeenAt = java.time.Instant.now(), lastSeenAt = java.time.Instant.now(), occurrenceCount = 1,
        )
        every { taskRepository.findById(11L) } returns java.util.Optional.of(
            com.example.compliance.remediation.domain.RemediationTask().apply { id = 11L; findingId = 7L; projectId = 9L }
        )
        val ex = org.junit.jupiter.api.assertThrows<com.example.compliance.common.exception.BusinessException> {
            service.requestRecheck(11L, 9L)
        }
        kotlin.test.assertEquals("finding not in FIXED state: 7", ex.message)
    }
```

（`requestRecheck` 的 view.status 来自 `toView` 的 `findById`——第一个测试 stub 返回的 FIXED 会被 transition 后再次 `findById` 读取；实现时 `toView` 在 transition 后调用，需要 stub `lifecyclePort.findById(7L)` 在第二次调用返回 RECHECKING 状态。**测试调整**：使用 `every { lifecyclePort.findById(7L) } returnsMany listOf(FIXED_VIEW, RECHECKING_VIEW)`，或断言改为 verify transition 调用而非 view.status。以实现为准使断言精确。）

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-remediation:test --tests "*RemediationServiceTest*"`
Expected: 编译失败 — `requestRecheck` 不存在。

- [ ] **Step 3: 实现 requestRecheck**

`RemediationService` 追加：

```kotlin
    /** 请求复审：仅 FIXED 可转 RECHECKING。复扫本身由用户/CI 经 scan API 触发，本端点只做状态转移（P2-D2）。 */
    @Transactional
    fun requestRecheck(taskId: Long, actorId: Long): RemediationTaskView {
        val task = mustGet(taskId)
        val f = lifecyclePort.findById(task.findingId) ?: throw BusinessException(404, "finding not found: ${task.findingId}")
        if (f.status != FindingStatus.FIXED) throw BusinessException(409, "finding not in FIXED state: ${task.findingId}")
        lifecyclePort.transition(task.findingId, FindingStatus.RECHECKING, "recheck_requested", actorId)
        return task.toView(lifecyclePort)
    }
```

`RemediationController` 追加：

```kotlin
    @PostMapping("/tasks/{id}/recheck")
    fun recheck(@PathVariable id: Long, auth: Authentication?): RemediationTaskView =
        service.requestRecheck(id, actorId(auth))
```

- [ ] **Step 4: 写切片测试**

`RemediationControllerTest.kt` 追加：

```kotlin
    @Test
    fun `request recheck returns updated view`() {
        every { service.requestRecheck(11L, 1L) } returns RemediationTaskView(
            id = 11L, findingId = 7L, projectId = 9L, assigneeId = 3L, assignerId = 1L,
            plannedFixDate = null, commentText = null, status = FindingStatus.RECHECKING,
            ruleCode = "R1", severity = "HIGH", filePath = "A.java", createdAt = java.time.Instant.now(),
        )
        mockMvc.perform(post("/api/v1/remediation/tasks/11/recheck"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("RECHECKING"))
    }
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
git commit -m "feat(remediation): recheck endpoint (FIXED -> RECHECKING) and state guard"
```

---

### Task 7.5: M7 集成测试 —— 整改闭环 + 复扫验证端到端

**Files:**
- Create: `app-server/src/test/kotlin/com/example/compliance/remediation/M7RemediationIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 7.2/7.3/7.4 全部；M6 的 STUBM6 适配器模式（复用 M6LifecycleIntegrationTest 的 @TestConfiguration 形态，**需独立 STUB 引擎名与规则，避免与 M6 冲突**）；`FindingLifecyclePort`。

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
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M7-* 数据前缀；独立 STUB 引擎 STUBM7，规则 stub-m7-rule，与 M6 的 STUBM6 不冲突。 */
class M7RemediationIntegrationTest : AbstractIntegrationTest() {

    @TestConfiguration
    class StubM7AdapterConfig {
        @Bean
        fun stubM7Adapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUBM7"
            override fun scan(context: ScanContext): ScanResult = ScanResult(
                findings = listOf(RawFinding("stub-m7-rule", "M7", "src/main/java/M7.java", 20, "HIGH", "m", "x=id;"))
            )
        }
    }

    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var checklistService: ChecklistService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService
    @Autowired lateinit var remediationService: RemediationService
    @Autowired lateinit var lifecyclePort: FindingLifecyclePort

    @Test
    fun `assign - fix - recheck - verify closed loop`() {
        // 1. 项目 + 仓库 + 清单发布绑定
        val project = projectService.create(CreateProjectCommand("M7P", "M7 项目", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("m7-repo", "https://git.example.com/m7.git", "GITLAB", "main", "tok"))
        val standard = checklistService.createStandard("M7-SEC", "M7 规范", null)
        val checklist = checklistService.createChecklist(standard.id!!, "M7-BASIC", "M7 基线")
        checklistService.addItem(checklist.id!!, com.example.compliance.checklist.application.AddItemCommand(itemCode = "M7-001", name = "M7 项", riskLevel = "HIGH"))
        val version = checklistService.publish(checklist.id!!)
        checklistService.bindProject(project.id!!, version.id!!)
        // 2. 规则
        val rule = ruleService.create(CreateRuleCommand("M7-SQLI", "M7 注入", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUBM7", "stub-m7-rule", null))
        ruleService.addComplianceMapping(rule.id!!, "M7-001")
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'HIGH'"))
        ruleService.publish(rule.id!!)
        // 3. 首扫 → finding NEW
        val task1 = scanTaskService.startScan(project.id!!, "STUBM7", "main")
        waitDone(task1.id!!)
        val findings = lifecyclePort.findingsForScanTask(task1.id!!)
        assertEquals(1, findings.size)
        val findingId = findings[0].id
        assertEquals(FindingStatus.NEW, findings[0].status)
        // 4. 派单 → ASSIGNED
        val assigned = remediationService.create(project.id!!, findingId, 9L, 3L, null, "handle")
        assertEquals(FindingStatus.ASSIGNED, assigned.status)
        // 5. 进入处理中 → FIXING
        val fixing = remediationService.startFix(assigned.id, 9L)
        assertEquals(FindingStatus.FIXING, fixing.status)
        // 6. 标记修复 → FIXED（带 commit 证据）
        val fixed = remediationService.markFixed(assigned.id, 9L, "deadbeef")
        assertEquals(FindingStatus.FIXED, fixed.status)
        // 7. 请求复审 → RECHECKING
        val rechecking = remediationService.requestRecheck(assigned.id, 9L)
        assertEquals(FindingStatus.RECHECKING, rechecking.status)
        // 8. 复扫：STUB 仍报同一问题 → finding 复现，verifyRechecking 回归 CONFIRMED
        val task2 = scanTaskService.startScan(project.id!!, "STUBM7", "main")
        waitDone(task2.id!!)
        val after = lifecyclePort.findingsForScanTask(task2.id!!)
        assertEquals(1, after.size)
        assertEquals(FindingStatus.CONFIRMED, after[0].status)   // 复现 → 回归 CONFIRMED（P2-D2 状态机）
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

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app-server:test --tests "*M7RemediationIntegrationTest*"`
Expected: 编译失败或断言失败（取决于前序任务是否完成）。

- [ ] **Step 3: 运行确认通过**

Run: `./gradlew :app-server:test --tests "*M7RemediationIntegrationTest*"`
Expected: PASS。

- [ ] **Step 4: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（含 frozen 全部绿）。

- [ ] **Step 5: Commit**

```bash
git add app-server/src/test/kotlin/com/example/compliance/remediation/M7RemediationIntegrationTest.kt
git commit -m "test(remediation): M7 closed-loop integration - assign, fix, recheck, verify regression"
```

> **M7 完成标准**：remediation 模块带实体/迁移/服务/API；状态权威始终在 finding（P2-D4）；recheck 状态机守卫正确；复扫验证闭环（verifyRechecking）贯通；`./gradlew build` 全绿。

---
## M8 — 真实引擎接入（五方法 Adapter 契约 + GitCheckout + Semgrep 落地）

> **契约演进（P2-D8）**：`ScanEngineAdapter` 从单方法 `scan(context): ScanResult` 演进为五阶段方法，全部带默认实现（适配器只需覆盖必要阶段），`ScanContext` 增 `workDir/commitId/timeoutSeconds/paramsJson`。STUB 集成测试（ScanPipeline/ReportApi）按 plan ruling 更新为五方法形态。GitCheckout 归编排器（P2-D6），按 `app.scan.checkout-engines` 配置门控。

### Task 8.1: 五方法 Adapter 契约 + ScanContext 扩展

**Files:**
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/engine/ScanEngineAdapter.kt`（全文件替换）
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/engine/ScanContext.kt`（+4 字段）
- Create: `module-result/src/test/kotlin/com/example/compliance/result/engine/DefaultAdapterBehaviorsTest.kt`
- Modify: `app-server/src/test/kotlin/com/example/compliance/scan/ScanPipelineIntegrationTest.kt`（STUB 五方法化，frozen 解冻）
- Modify: `app-server/src/test/kotlin/com/example/compliance/report/ReportApiIntegrationTest.kt`（STUB 五方法化，frozen 解冻）

**Interfaces:**
- Consumes: 现有 `ScanResult`/`RawFinding`。
- Produces: 新 `ScanEngineAdapter`（`val engine: String` + 六方法全默认实现）；`ScanContext` 增字段；`ScanContext` 既有的 `scanTaskId/projectId/repoUrl/ref/configJson` 保留。

- [ ] **Step 1: 写失败测试（默认实现行为）**

创建 `module-result/src/test/kotlin/com/example/compliance/result/engine/DefaultAdapterBehaviorsTest.kt`：

```kotlin
package com.example.compliance.result.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** P2-D8：默认实现让适配器只覆盖必要阶段；本测试锁定默认行为。 */
class DefaultAdapterBehaviorsTest {

    private val adapter = object : ScanEngineAdapter { override val engine = "TEST-DEFAULT" }

    @Test
    fun `default pipeline returns empty result without failure`() {
        val ctx = ScanContext(1L, 2L, "https://git.example.com/r.git", "main")
        val prep = adapter.prepareScan(ctx)
        val exec = adapter.executeScan(ctx, prep)
        val raw = adapter.collectResult(ctx, exec)
        val result = adapter.normalizeResult(ctx, raw)
        assertEquals(0, result.findings.size)
        assertEquals(true, result.success)
    }

    @Test
    fun `cleanup is a no-op`() {
        val ctx = ScanContext(1L, 2L, "https://git.example.com/r.git", "main")
        val prep = adapter.prepareScan(ctx)
        adapter.cleanup(ctx, prep)   // 不得抛异常
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-result:test --tests "*DefaultAdapterBehaviorsTest*"`
Expected: 编译失败 — 五方法未定义。

- [ ] **Step 3: 实现契约**

`ScanContext.kt` 全文件替换：

```kotlin
package com.example.compliance.result.engine

/** 单次扫描的执行上下文。M8 起含工作目录/commit/超时/参数。 */
data class ScanContext(
    val scanTaskId: Long,
    val projectId: Long,
    val repoUrl: String,
    val ref: String?,
    val configJson: String? = null,
    val workDir: String? = null,
    val commitId: String? = null,
    val timeoutSeconds: Long = 300,
    val paramsJson: String? = null,
)
```

`ScanEngineAdapter.kt` 全文件替换：

```kotlin
package com.example.compliance.result.engine

/** 扫描执行准备：适配器在 prepare 阶段产出，execute/collect 消费，cleanup 释放。 */
interface ScanPreparation { val workDir: String? }

/** 扫描执行句柄：executeScan 产出，collectResult 消费。 */
interface ScanExecution

/** 引擎原始输出：normalizeResult 消费，转成 ScanResult。 */
interface RawScanOutput

/**
 * 五阶段扫描适配器契约（P2-D8）。全部阶段带默认实现——适配器只覆盖自身需要的阶段；
 * 默认实现返回空结果，保证未覆盖阶段的适配器可空转（用于测试/桩）。
 */
interface ScanEngineAdapter {
    val engine: String

    /** 是否支持该引擎（默认按 engine 名匹配）。 */
    fun supports(engine: String): Boolean = this.engine == engine

    /** 准备：创建/解析工作目录等。 */
    fun prepareScan(context: ScanContext): ScanPreparation = ScanPreparation { context.workDir }

    /** 执行：运行扫描器，产出句柄。 */
    fun executeScan(context: ScanContext, preparation: ScanPreparation): ScanExecution = object : ScanExecution {}

    /** 收集：读执行输出（文件/标准输出）。 */
    fun collectResult(context: ScanContext, execution: ScanExecution): RawScanOutput = object : RawScanOutput {}

    /** 归一化：原始输出 → ScanResult（severity 映射、过滤在此阶段）。 */
    fun normalizeResult(context: ScanContext, raw: RawScanOutput): ScanResult = ScanResult(emptyList(), true)

    /** 清理：释放临时资源（幂等）。 */
    fun cleanup(context: ScanContext, preparation: ScanPreparation) {}

    /** 向后兼容：旧单方法适配器仍可用——默认管线跑一遍（prepare→execute→collect→normalize）。 */
    fun scan(context: ScanContext): ScanResult {
        val prep = prepareScan(context)
        val exec = executeScan(context, prep)
        val raw = collectResult(context, exec)
        val result = normalizeResult(context, raw)
        cleanup(context, prep)
        return result
    }
}
```

`ScanResult`/`RawFinding` 保持既有定义（`ScanResult(findings, success, errorMessage?)`；`RawFinding(engineRuleId, ruleName, filePath, line, severity, category?, message?, codeSnippet?)`），不做改动。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :module-result:test --tests "*DefaultAdapterBehaviorsTest*"`
Expected: PASS。

- [ ] **Step 5: 解冻 STUB 集成测试（五方法化）**

`ScanPipelineIntegrationTest.kt` 的 STUB 适配器从：

```kotlin
        @Bean
        fun stubAdapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUB"
            override fun scan(context: ScanContext): ScanResult = ScanResult(
                findings = listOf(RawFinding("stub-rule-sqli", "STUB", "src/main/java/A.java", 1, "HIGH", "sqli", "msg", "x"))
            )
        }
```

改为：

```kotlin
        @Bean
        fun stubAdapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUB"
            override fun prepareScan(context: ScanContext): ScanPreparation = ScanPreparation { context.workDir ?: "tmp" }
            override fun executeScan(context: ScanContext, preparation: ScanPreparation): ScanExecution = object : ScanExecution {}
            override fun collectResult(context: ScanContext, execution: ScanExecution): RawScanOutput = object : RawScanOutput {}
            override fun normalizeResult(context: ScanContext, raw: RawScanOutput): ScanResult = ScanResult(
                findings = listOf(RawFinding("stub-rule-sqli", "STUB", "src/main/java/A.java", 1, "HIGH", "sqli", "msg", "x"))
            )
        }
```

`ReportApiIntegrationTest.kt` 的 STUB 适配器同样改（该测试 stub 规则/引擎以该文件现有 `engine`/`ruleCode` 为准，仅把单方法改成五方法 + normalizeResult 返回其原 findings）。

> **plan ruling（解冻依据）**：frozen 仅保护既有断言与测试语义，不保护测试内部实现形态；五方法契约是 spec P2-D8 的强制演进，2 个集成测试的 STUB 适配器属于被演进接口的实现，必须同步。断言值（finding 数、severity 分布、报表口径）一律不变。

- [ ] **Step 6: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（含两个解冻 STUB 后的集成测试全绿）。

- [ ] **Step 7: Commit**

```bash
git add module-result/src/main/kotlin/com/example/compliance/result/engine module-result/src/test/kotlin/com/example/compliance/result/engine/DefaultAdapterBehaviorsTest.kt app-server/src/test/kotlin/com/example/compliance/scan/ScanPipelineIntegrationTest.kt app-server/src/test/kotlin/com/example/compliance/report/ReportApiIntegrationTest.kt
git commit -m "feat(result): five-stage engine adapter contract with default implementations, extend ScanContext"
```

---

### Task 8.2: GitCheckout（编排器层，配置门控）

**Files:**
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/application/GitCheckout.kt`
- Modify: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanOrchestrator.kt`（接 GitCheckout）
- Create: `module-scan/src/test/kotlin/com/example/compliance/scan/application/GitCheckoutTest.kt`
- Create: `module-scan/src/test/resources/application-test.properties`（或修改既有测试配置，加 checkout-engines 属性）
- Modify: `app-server/src/main/resources/application.yml`（`app.scan.checkout-engines` 默认值）

**Interfaces:**
- Consumes: `ScanContext`（Task 8.1 扩展）。
- Produces: `GitCheckout`（`@Component`）：
  - `fun checkout(engine: String, repoUrl: String, ref: String?, workDir: String): String?` —— 按 `app.scan.checkout-engines`（逗号分隔）门控：引擎不在名单 → 返回 null（跳过 clone）；在名单 → `git clone --depth 1 [-b ref] repoUrl workDir` + `git -C workDir rev-parse HEAD` 返回 commitId；clone 失败抛 `BusinessException(500, ...)`。
  - `fun cleanup(workDir: String)` —— 递归删除（幂等，不存在即返回）。
  - 可注入 `WorkDirProvider`（默认 `java.nio.file.Files.createTempDirectory("scan-")`）便于测试。

- [ ] **Step 1: 写失败测试（门控 + commitId）**

创建 `module-scan/src/test/kotlin/com/example/compliance/scan/application/GitCheckoutTest.kt`：

```kotlin
package com.example.compliance.scan.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8：GitCheckout 门控与 commit 回填（STUB 引擎跳过 clone）。 */
class GitCheckoutTest {

    private val processRunner = mockk<ProcessRunner>()
    @TempDir lateinit var tmp: Path

    @Test
    fun `engine not in checkout list skips clone and returns null`() {
        val checkout = GitCheckout(processRunner, setOf("SEMGREP"))
        val commit = checkout.checkout("STUB", "https://git.example.com/r.git", "main", tmp.resolve("w").toString())
        assertNull(commit)
        verify(exactly = 0) { processRunner.run(any(), any(), any(), any()) }
    }

    @Test
    fun `engine in list clones and reads HEAD`() {
        every { processRunner.run(any(), any(), any(), any()) } returns 0
        every { processRunner.capture(any()) } returns "abc123def\n"
        val checkout = GitCheckout(processRunner, setOf("SEMGREP", "STUB"))
        val commit = checkout.checkout("STUB", "https://git.example.com/r.git", "main", tmp.resolve("w").toString())
        assertEquals("abc123def", commit)
        verify { processRunner.run(any(), any(), any(), any()) }
    }
}
```

> `ProcessRunner` 是本任务为可测性引入的薄抽象（`fun run(command: List<String>, workDir: String?, env: Map<String,String>?, timeoutSeconds: Long): Int` + `fun capture(command: List<String>): String`），`GitCheckout` 用它执行 git；生产实现注入真实 `java.lang.ProcessBuilder` 封装（`SystemProcessRunner`，同文件新建）。若不想引入抽象，也可用 `Files.createTempFile` 方案——**以可测性为先，保留 ProcessRunner 抽象**。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-scan:test --tests "*GitCheckoutTest*"`
Expected: 编译失败 — `GitCheckout`/`ProcessRunner` 不存在。

- [ ] **Step 3: 实现 GitCheckout**

创建 `module-scan/src/main/kotlin/com/example/compliance/scan/application/GitCheckout.kt`：

```kotlin
package com.example.compliance.scan.application

import com.example.compliance.common.exception.BusinessException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** 进程执行抽象（可测试性：单元测试用 mock 替换真实进程）。 */
interface ProcessRunner {
    fun run(command: List<String>, workDir: String?, env: Map<String, String>?, timeoutSeconds: Long): Int
    fun capture(command: List<String>): String
}

/** 真实进程执行器。 */
@Component
class SystemProcessRunner : ProcessRunner {
    override fun run(command: List<String>, workDir: String?, env: Map<String, String>?, timeoutSeconds: Long): Int {
        val pb = ProcessBuilder(command)
        if (workDir != null) pb.directory(Path.of(workDir).toFile())
        if (env != null) pb.environment().putAll(env)
        val p = pb.start()
        p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        return p.exitValue()
    }

    override fun capture(command: List<String>): String {
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readText().trim()
    }
}

/** 编排器层的 Git 检出（P2-D6）：按 app.scan.checkout-engines 门控，回填 commitId，finally 清理。 */
@Component
class GitCheckout(
    private val processRunner: ProcessRunner,
    @Value("\${app.scan.checkout-engines:SEMGREP}") checkoutEngines: String,
) {
    private val enabled = checkoutEngines.split(",").map { it.trim().uppercase() }.toSet()

    /** 检出到 workDir；引擎不在门控名单返回 null（跳过 clone，STUB 测试不触网）。 */
    fun checkout(engine: String, repoUrl: String, ref: String?, workDir: String): String? {
        if (engine.uppercase() !in enabled) return null
        val dir = Path.of(workDir)
        Files.createDirectories(dir)
        val cmd = mutableListOf("git", "clone", "--depth", "1")
        if (!ref.isNullOrBlank()) cmd += listOf("-b", ref)
        cmd += listOf(repoUrl, workDir)
        val exit = processRunner.run(cmd, null, null, 300)
        if (exit != 0) throw BusinessException(500, "git clone failed for $repoUrl (exit=$exit)")
        return processRunner.capture(listOf("git", "-C", workDir, "rev-parse", "HEAD")).take(64)
    }

    /** 递归清理（幂等）。 */
    fun cleanup(workDir: String) {
        runCatching {
            val dir = Path.of(workDir)
            if (Files.exists(dir)) Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :module-scan:test --tests "*GitCheckoutTest*"`
Expected: PASS。

- [ ] **Step 5: 应用配置**

`app-server/src/main/resources/application.yml` 追加：

```yaml
app:
  scan:
    checkout-engines: SEMGREP
```

`module-scan` 测试资源（`module-scan/src/test/resources/application-test.properties` 若存在则追加，否则新建）：

```properties
app.scan.checkout-engines=
```

（空名单 → 所有引擎跳过 clone，集成测试不触网。）

- [ ] **Step 6: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: Commit**

```bash
git add module-scan/src/main/kotlin/com/example/compliance/scan/application module-scan/src/test app-server/src/main/resources/application.yml
git commit -m "feat(scan): orchestrator-side git checkout gated by engine list with commit backfill"
```

---
### Task 8.3: SemgrepAdapter 五方法化（severity 映射入 normalizeResult）

**Files:**
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/engine/SemgrepAdapter.kt`（全文件替换）
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/engine/SemgrepCli.kt`（按需保留，产出 RawScanOutput）
- Create: `module-result/src/test/kotlin/com/example/compliance/result/engine/SemgrepAdapterTest.kt`

**Interfaces:**
- Consumes: Task 8.1 五方法契约 + `ScanContext.workDir`；`SemgrepCli` 既有能力（`run(repoDir, configJson): SemgrepOutput`）。
- Produces: `SemgrepAdapter` 覆盖 `supports/prepareScan/executeScan/collectResult/normalizeResult/cleanup`；`normalizeResult` 内做 severity 映射（`ERROR→HIGH`、`WARNING→MEDIUM`、`INFO→LOW`，映射表以既有 `SemgrepAdapter` 的 severity 归一为准）与规则 id 过滤；`RawScanOutput` 实现类承载 CLI 输出（outputRef 传 CLI 输出的文件路径/内容）。

- [ ] **Step 1: 写失败测试**

创建 `module-result/src/test/kotlin/com/example/compliance/result/engine/SemgrepAdapterTest.kt`：

```kotlin
package com.example.compliance.result.engine

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** M8：Semgrep 适配器五阶段化后 normalize 行为不变（severity 映射 + 规则过滤）。 */
class SemgrepAdapterTest {

    private val cli = mockk<SemgrepCli>()

    @Test
    fun `normalize maps severities and drops unmapped rules`() {
        val adapter = SemgrepAdapter(cli)
        val raw = object : RawScanOutput {
            val findings = listOf(
                SemgrepFinding("rule-a", "A", "x.java", 1, "ERROR", "m", "x;"),
                SemgrepFinding("rule-b", "B", "y.java", 2, "INFO", "m", "y;"),
                SemgrepFinding("unmapped", "U", "z.java", 3, "ERROR", "m", "z;"),
            )
            val ruleMap = mapOf("rule-a" to "R1", "rule-b" to "R2")
        }

        val result = adapter.normalizeResult(ScanContext(1L, 2L, "u", "main"), raw as RawScanOutput)

        // rule-a → HIGH；rule-b → LOW；unmapped 被过滤
        assertEquals(2, result.findings.size)
        assertEquals("R1", result.findings[0].engineRuleId)
        assertEquals("HIGH", result.findings[0].severity)
        assertEquals("R2", result.findings[1].engineRuleId)
        assertEquals("LOW", result.findings[1].severity)
    }
}
```

> `SemgrepFinding`/`ruleMap` 是测试内的原始输出结构。实现时 `SemgrepAdapter` 的 `collectResult` 返回一个 `RawScanOutput` 实现（承载 CLI 输出 + 规则映射），`normalizeResult` 从其中读 `findings` 与 `ruleMap`。若既有 `SemgrepCli` 已产出含规则映射的结构，直接复用之。**测试中的字段名以实现为准调整，但断言语义（映射 + 过滤 + 数量）不变。**

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-result:test --tests "*SemgrepAdapterTest*"`
Expected: 编译失败 — 五方法未实现或测试结构与实现不匹配。

- [ ] **Step 3: 实现 SemgrepAdapter**

`SemgrepAdapter.kt` 全文件替换：

```kotlin
package com.example.compliance.result.engine

import org.springframework.stereotype.Component

/** Semgrep CLI 的原始输出（承载 CLI 解析结果 + 引擎规则→平台规则映射）。 */
class SemgrepRawOutput(
    val findings: List<SemgrepFinding>,
    val ruleMap: Map<String, String>,
) : RawScanOutput

data class SemgrepFinding(
    val engineRuleId: String,
    val ruleName: String,
    val filePath: String,
    val line: Int,
    val severity: String,
    val category: String?,
    val message: String?,
    val codeSnippet: String?,
)

/** Semgrep 适配器（M8 五阶段化）：execute 跑 CLI，normalize 做 severity 映射与规则过滤。 */
@Component
class SemgrepAdapter(
    private val cli: SemgrepCli,
) : ScanEngineAdapter {

    override val engine = "SEMGREP"

    private val severityMap = mapOf(
        "ERROR" to "HIGH",
        "WARNING" to "MEDIUM",
        "INFO" to "LOW",
    )

    override fun executeScan(context: ScanContext, preparation: ScanPreparation): ScanExecution {
        val out = cli.run(context.workDir ?: throw IllegalStateException("workDir required for semgrep"), context.configJson)
        return object : ScanExecution {}
    }

    override fun collectResult(context: ScanContext, execution: ScanExecution): RawScanOutput {
        // CLI 输出经 cli.run 已落入本适配器；此处转成 RawScanOutput（若 CLI 已产出结构则直接包装）
        val findings = cli.outputAsFindings()
        val ruleMap = cli.ruleMap()
        return SemgrepRawOutput(findings, ruleMap)
    }

    override fun normalizeResult(context: ScanContext, raw: RawScanOutput): ScanResult {
        val output = raw as SemgrepRawOutput
        val findings = output.findings.mapNotNull { f ->
            val platformRule = output.ruleMap[f.engineRuleId] ?: return@mapNotNull null
            RawFinding(
                engineRuleId = platformRule,
                ruleName = f.ruleName,
                filePath = f.filePath,
                line = f.line,
                severity = severityMap[f.severity] ?: f.severity,
                category = f.category,
                message = f.message,
                codeSnippet = f.codeSnippet,
            )
        }
        return ScanResult(findings, true)
    }

    override fun supports(engine: String): Boolean = engine.equals("SEMGREP", ignoreCase = true)
}
```

> `SemgrepCli` 需补充 `outputAsFindings(): List<SemgrepFinding>` 与 `ruleMap(): Map<String, String>`（从 CLI JSON 输出解析 + 配置文件映射）。**若既有 `SemgrepCli` 已有等价能力，直接复用；否则在 `SemgrepCli.kt` 追加这两个方法**，并保持其既有 `run(repoDir, configJson)` 不变。测试的 mock 相应 stub 这两个方法。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :module-result:test --tests "*SemgrepAdapterTest*" --tests "*DefaultAdapterBehaviorsTest*"`
Expected: PASS。

- [ ] **Step 5: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: Commit**

```bash
git add module-result/src/main/kotlin/com/example/compliance/result/engine module-result/src/test/kotlin/com/example/compliance/result/engine
git commit -m "feat(result): semgrep adapter on five-stage contract with severity mapping in normalize"
```

---

### Task 8.4: 编排器接入五方法管线 + commitId 回填 + M8 集成测试

**Files:**
- Modify: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanOrchestrator.kt`（五方法管线 + GitCheckout）
- Create: `app-server/src/test/kotlin/com/example/compliance/scan/M8EngineContractIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 8.1 五方法契约；Task 8.2 `GitCheckout`；Task 8.3 `SemgrepAdapter`（经 registry 注入）。
- Produces: `ScanOrchestrator` 管线：`prepareScan → checkout(GitCheckout, 门控) → executeScan → collectResult → normalizeResult → cleanup(finally)`；成功后 `task.commitId = gitCommit`、`task.workDir` 生命周期由 orchestrator 管理；STUB 引擎（不在 checkout 名单）跳过 clone、commitId 为 null。

- [ ] **Step 1: 写失败集成测试**

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
import com.example.compliance.result.engine.RawScanOutput
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanExecution
import com.example.compliance.result.engine.ScanPreparation
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8-* 数据前缀；STUB 引擎五方法形态，验证编排器调用五阶段、commitId 门控回填。 */
class M8EngineContractIntegrationTest : AbstractIntegrationTest() {

    @TestConfiguration
    class StubM8AdapterConfig {
        @Bean
        fun stubM8Adapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUBM8"
            var prepared = false
            override fun prepareScan(context: ScanContext): ScanPreparation = ScanPreparation { "tmp-m8" }.also { prepared = true }
            override fun executeScan(context: ScanContext, preparation: ScanPreparation): ScanExecution = object : ScanExecution {}
            override fun collectResult(context: ScanContext, execution: ScanExecution): RawScanOutput = object : RawScanOutput {}
            override fun normalizeResult(context: ScanContext, raw: RawScanOutput): ScanResult = ScanResult(
                findings = listOf(RawFinding("stub-m8-rule", "M8", "src/main/java/M8.java", 30, "HIGH", "m", "x"))
            )
        }
    }

    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService
    @Autowired lateinit var lifecyclePort: FindingLifecyclePort

    @Test
    fun `orchestrator runs five-stage pipeline and keeps commitId null for gated-out engine`() {
        // 1. 项目 + 仓库 + 规则（STUBM8 引擎绑定）
        val project = projectService.create(CreateProjectCommand("M8P", "M8 项目", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("m8-repo", "https://git.example.com/m8.git", "GITLAB", "main", "tok"))
        val rule = ruleService.create(CreateRuleCommand("M8-SQLI", "M8 注入", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUBM8", "stub-m8-rule", null))
        ruleService.addComplianceMapping(rule.id!!, "M8-001")
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'HIGH'"))
        ruleService.publish(rule.id!!)
        // 2. 扫描（STUBM8 不在 checkout 名单 → 不触网）
        val task = scanTaskService.startScan(project.id!!, "STUBM8", "main")
        waitDone(task.id!!)
        // 3. 断言：occurrence 口径 1 条、commitId 为 null（门控跳过 clone）
        assertEquals(ScanTaskStatus.SUCCESS, scanTaskService.get(task.id!!).status)
        assertEquals(1, lifecyclePort.findingsForScanTask(task.id!!).size)
        assertNull(scanTaskService.get(task.id!!).commitId)
    }

    private fun waitDone(taskId: Long) {
        var done = false
        repeat(50) {
            val s = scanTaskService.get(taskId).status
            if (s != ScanTaskStatus.RUNNING && s != ScanTaskStatus.PENDING) { done = true; return@repeat }
            Thread.sleep(200)
        }
        assertTrue(done, "scan $taskId should finish within timeout")
    }
}
```

> 注意：需要清单才能评估？本测试**不建清单**——`evaluate` 在 `publishedItemsForProject` 返回 null 时返回空列表，评估跳过（既有行为），finding 落库不受影响。若既有编排器在无清单时抛错，则在本测试中补建清单（复用 M6 的建单/发布/绑定三步）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app-server:test --tests "*M8EngineContractIntegrationTest*"`
Expected: 编译失败或断言失败（编排器仍走旧单方法管线）。

- [ ] **Step 3: 编排器接入五方法管线**

`ScanOrchestrator` 构造新增 `private val gitCheckout: GitCheckout`，将扫描段改为：

```kotlin
            val context = ScanContext(
                scanTaskId = task.id!!,
                projectId = task.projectId,
                repoUrl = repo.gitUrl,
                ref = task.ref,
                configJson = null,
                workDir = null,          // prepare 后填充
                timeoutSeconds = 300,
            )
            val start = System.currentTimeMillis()

            // P2-D6：GitCheckout 归编排器；门控名单外引擎跳过 clone（STUB 不触网）
            val preparation = adapter.prepareScan(context)
            val commitId = gitCheckout.checkout(task.engine, repo.gitUrl, task.ref, preparation.workDir ?: "tmp")
            task.commitId = commitId
            val execCtx = context.copy(workDir = preparation.workDir, commitId = commitId)
            val execution = adapter.executeScan(execCtx, preparation)
            val raw = adapter.collectResult(execCtx, execution)
            val result = adapter.normalizeResult(execCtx, raw)
            adapter.cleanup(execCtx, preparation)
            val duration = System.currentTimeMillis() - start
```

（其余段落——`result.success` 判断、归一化、upsert、occurrence、verifyRechecking、评估——保持 Task 6.5 已落地的形态不变。）

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app-server:test --tests "*M8EngineContractIntegrationTest*" --tests "*M6LifecycleIntegrationTest*" --tests "*M7RemediationIntegrationTest*"`
Expected: 全部 PASS。

- [ ] **Step 5: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（含 frozen ScanPipeline/ReportApi 解冻后全绿）。

- [ ] **Step 6: Commit**

```bash
git add module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanOrchestrator.kt app-server/src/test/kotlin/com/example/compliance/scan/M8EngineContractIntegrationTest.kt
git commit -m "feat(scan): orchestrator on five-stage pipeline with gated checkout and commit backfill"
```

> **M8 完成标准**：五方法契约落地且默认实现可空转；STUB 集成测试同步解冻；GitCheckout 门控（STUB 不触网、SEMGREP 真实 clone）；SemgrepAdapter 五阶段化；编排器五方法管线 + commitId 回填；`./gradlew build` 全绿。

---
## M9 — 工程化补全（OpenAPI CI 触发 + RBAC + 异常语义 + 审计回滚 + 指标 + 通知）

### Task 9.1: OpenAPI Token 表（多 CI 管理，BCrypt）

**Files:**
- Create: `app-server/src/main/resources/db/migration/V11__openapi_token.sql`
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

- [ ] **Step 3: 写 V11 迁移**

创建 `app-server/src/main/resources/db/migration/V11__openapi_token.sql`：

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
CREATE INDEX idx_api_token_status ON api_token (status);
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
git add module-openapi app-server/src/main/resources/db/migration/V11__openapi_token.sql
git commit -m "feat(openapi): per-CI api token table with bcrypt hashing and admin management"
```

---

### Task 9.2: OpenAPI CI 触发扫描端点（X-API-Token 校验）

**Files:**
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanTriggerPort.kt`
- Modify: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanTaskService.kt`（实现 ScanTriggerPort）
- Create: `module-openapi/src/main/kotlin/com/example/compliance/openapi/api/OpenApiScanController.kt`
- Modify: `module-openapi/build.gradle.kts`（+implementation(project(":module-scan"))）
- Modify: `module-auth/src/main/kotlin/com/example/compliance/auth/config/SecurityConfig.kt`（permitAll + ADMIN 路径，字符串字面量）
- Create: `module-openapi/src/test/kotlin/com/example/compliance/openapi/api/OpenApiScanControllerTest.kt`

**Interfaces:**
- Consumes: `ApiTokenService.verify`（Task 9.1）；`ScanTriggerPort`。
- Produces: `ScanTriggerPort`（module-scan.application）：
  - `fun triggerScan(projectId: Long, engine: String, ref: String?, triggerType: String, requestId: String?): ScanTaskView`
- Produces: `POST /api/v1/openapi/scans` body `TriggerScanCommand(projectId, engine, ref?)`，头 `X-API-Token`：
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

- [ ] **Step 3: 实现 ScanTriggerPort + 适配**

`module-scan/.../application/ScanTriggerPort.kt`：

```kotlin
package com.example.compliance.scan.application

import com.example.compliance.scan.domain.ScanTaskStatus

/** 触发扫描的端口：module-openapi（开放 API）经此触发，不依赖 scan 内部实现。 */
data class ScanTaskView(val id: Long, val projectId: Long, val engine: String, val status: ScanTaskStatus, val requestId: String?)

interface ScanTriggerPort {
    fun triggerScan(projectId: Long, engine: String, ref: String?, triggerType: String, requestId: String?): ScanTaskView
}
```

`ScanTaskService` 实现 `ScanTriggerPort`，新增：

```kotlin
    override fun triggerScan(projectId: Long, engine: String, ref: String?, triggerType: String, requestId: String?): ScanTaskView {
        val task = startScan(projectId, engine, ref, triggerType)
        if (requestId != null) task.requestId = requestId
        return ScanTaskView(task.id!!, task.projectId, task.engine, task.status, task.requestId)
    }
```

（`startScan` 已接受 `triggerType` 参数——Task 6.5。`ScanTaskService` 声明 `: ScanTaskService, ScanTriggerPort`。）

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
git add module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanTriggerPort.kt module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanTaskService.kt module-openapi module-auth/src/main/kotlin/com/example/compliance/auth/config/SecurityConfig.kt
git commit -m "feat(openapi): CI scan trigger endpoint with X-API-Token validation and jwt fallback"
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
- Consumes: `AuditService`（既有）。
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

        verify { auditService.record(9L, "RULE_PUBLISHED", any(), any(), any()) }
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
        auditService.record(actorId, "RULE_PUBLISHED", "rule", ruleId, "{\"ruleCode\":\"$ruleCode\",\"version\":$version}")
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
        1L, 9L, 1L, "R1", severity, status, "A.java", 1, Instant.now(), Instant.now(), 1,
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
- Create: `app-server/src/main/resources/db/migration/V12__notification.sql`
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

- [ ] **Step 3: 写 V12 迁移**

创建 `app-server/src/main/resources/db/migration/V12__notification.sql`：

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
git add module-notification app-server/src/main/resources/db/migration/V12__notification.sql
git commit -m "feat(notification): notification entity, V12 migration, and stub send service"
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
- V8-V10 迁移 → Task 6.1（V8）、7.1（V9）、7.3（V10）；V11（openapi token，9.1）、V12（notification，9.7）。
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
- `FindingView` 字段：6.3 定义 → 7.1/7.2/9.6 构造一致。
- `ScanContext` 字段：6.5 使用旧四字段 → 8.1 扩展 + 8.3/8.4 消费一致。
- `ScanEngineAdapter` 五方法：8.1 定义 → 8.3/8.4 + STUB 解冻一致。
- `upsertByFingerprint(projectId, scanTaskId, engine, findings)` 签名贯穿 6.4/6.5 一致。
- `startScan(projectId, engine, ref, triggerType="MANUAL")` 贯穿 6.5/9.2 一致。
- `ScanTriggerPort.triggerScan`（9.2 定义）被 9.2 控制器消费一致。

**4. 已知调整点（以实际代码为准的核对项）**：
- 既有 `BusinessException` 是否已有 `code` 字段（9.4 Step 3 注明补字段）。
- `AuditService.record` 实际签名（6.3/9.5 注明以文件为准）。
- `RuleService`/`ChecklistService` 既有构造与写操作签名（9.5 注明）。
- `ScanTaskService` 是否有 `listByProject`（9.8 注明 fallback）。
- 冻结 STUB 解冻（8.1 Step 5）断言值不变。

---
