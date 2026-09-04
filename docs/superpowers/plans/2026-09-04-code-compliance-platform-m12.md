# M12 合规报告快照 + 报告模板（版本化）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 module-report 现有基础报表（scan-summary/compliance-summary/trend）之上落地报告模板版本化与报告快照：模板管理（DRAFT→PUBLISHED→DISABLED）+ 快照生成落库（不可变）+ 查询/导出（JSON/HTML）。

**Architecture:** 版本化镜像 `checklist_version` 既有先例（主表 + 版本子表 + `VersionStatus` 枚举复用 + audit）；快照按 (模板版本 + 项目/任务 + 生成时刻) 固化指标数据（payload JSONB，经既有 `ReportMetrics` 统一口径），生成后不可变（不暴露任何 update/delete）。模板驱动的意义在于：生成只取该类型 **PUBLISHED 最新版**并记模板版本号（可追溯），sections 负载留作审计与后续 HTML 渲染。

**Tech Stack:** Kotlin 2.0.21 / Spring Boot 3.3.5 / Spring Data JPA / Flyway V13 / PostgreSQL 16（Testcontainers）/ JUnit 5 + MockK + MockMvc。全部 Gradle 命令用 `./gradlew`（wrapper 8.8）。

**Spec:** `docs/superpowers/specs/2026-09-04-code-compliance-platform-m12-m14-design.md`（§3 M12 设计 + §7 全局约束）

## Global Constraints

以下约束对每个任务隐式生效（逐字复制自 spec §7 与本仓库已确立裁决）：

1. **模块依赖**：module-report 依赖 module-common/module-scan/module-result/module-checklist（现状）；跨模块只 import 接口/值类型/枚举，**绝不 import `@Entity`**（P2-D5）。`VersionStatus` 直接从 `com.example.compliance.checklist.domain.VersionStatus` 复用（module-report 已依赖 module-checklist，无需迁移）。
2. **模块内分层**：api/application/domain/infrastructure；Controller 不写业务逻辑、不返回 Entity。
3. **统一 API**：响应 `{code:0,message:"success",data}`；分页 `{items,page,size,total}`（`PageResponse`，module-common）；路径 `/api/v1/reports/...`。
4. **表约定**：业务表含 `id/created_at/updated_at`（`BaseEntity`）；`audit_log` 只增不改不删；**Ruling #34**：`audit_log.detail` 是 JSONB，detail 必须传合法 JSON 字符串。
5. **枚举统一**：`VersionStatus=DRAFT/PUBLISHED/DISABLED`（module-checklist）；`FindingStatus` 11 态不变；severity 原生透传、仅 normalizeResult 映射（本里程碑不涉及）。
6. **jsonb 绑定（Ruling #13/#25）**：实体 String 字段映射 jsonb 列**必须**加 `@JdbcTypeCode(SqlTypes.JSON)`，否则 INSERT 报 "column is of type jsonb but expression is of type character varying"（镜像 `ChecklistVersion.contentSnapshot` 先例）。这是本计划数据层任务的核心陷阱。
7. **安全**：除 `login`/`swagger`/`/actuator/health`/`/api/v1/openapi/scans` 外全部 JWT；报告导出需鉴权。**Ruling #49**：MockMvc HTTP 测试必须 `@WithMockUser`（走完整 Security 链的集成测试；`addFilters=false` 的切片测试不受限，但负例 RBAC 必须在完整链测试断言）。
8. **RBAC**（spec §3.3 三档）：`templates/*/disable` 仅 `ADMIN`；`templates/*/versions` = `ADMIN`/`COMPLIANCE_MANAGER`/`AUDITOR`；`templates/**`（draft/publish） = `ADMIN`/`COMPLIANCE_MANAGER`。SecurityConfig 路径匹配**按此顺序声明**（disable→versions→general，首匹配生效）。生成/查询/导出端点认证用户即可。`AuthPrincipal.hasRole` 语义：`"ROLE_$role" in authorities`。
9. **Ruling #45/#52**：编排器路径不添加 `@Transactional`（本里程碑不涉及编排器；服务层正常 `@Transactional` 与 ChecklistService 先例一致）。
10. **共享 Testcontainers**：app-server 集成测试共享 PG 容器（`max_connections=300` 保持）；数据全局唯一前缀 **`M12-*`**；`SmokeFirstClassOrderer` 不变。
11. **R-M11-1**：实体 `BigDecimal?` ↔ NUMERIC 列（本里程碑 ComplianceSummary 沿用 `score: BigDecimal?` 现状，不改）。
12. **审计**：模板发布记 audit（`AuditService.record`，镜像 ChecklistService.publish）；快照只增不改不删（不暴露 update/delete）。
13. **测试形态先例**：Controller 切片 = `@WebMvcTest(Controller::class)` + `@AutoConfigureMockMvc(addFilters=false)` + `@TestConfiguration @Bean fun service(): X = mockk()`（MockK，勿用 @MockBean）；仓储集成测试 = app-server `extends AbstractIntegrationTest()` + `@Autowired` 仓储。

---
## 文件结构总览

| 任务 | 文件 | 职责 |
|---|---|---|
| 12.1 | `V13__report_snapshot_template.sql` | 三表 DDL |
| 12.1 | `module-report/.../domain/ReportTemplate.kt`、`ReportTemplateVersion.kt`、`ReportSnapshot.kt` | 实体（jsonb 注解） |
| 12.1 | `module-report/.../infrastructure/ReportTemplateRepository.kt`、`ReportTemplateVersionRepository.kt`、`ReportSnapshotRepository.kt` | 仓储 |
| 12.1 | `app-server/src/test/.../report/ReportRepositoryIntegrationTest.kt` | jsonb 往返 + 查询（Testcontainers） |
| 12.2 | `module-report/.../application/ReportTemplateService.kt` | 模板版本状态机（draft/publish/disable/versions） |
| 12.2 | `module-report/.../api/ReportTemplateController.kt` + `api/dto/ReportTemplateDtos.kt` | 模板管理端点 |
| 12.2 | `module-auth/.../config/SecurityConfig.kt` | templates 路径 RBAC |
| 12.2 | `module-report/build.gradle.kts` | + jackson-module-kotlin testImplementation |
| 12.2 | `module-report/src/test/.../application/ReportTemplateServiceTest.kt`、`api/ReportTemplateControllerTest.kt` | 版本机 + 端点切片 |
| 12.3 | `module-report/.../application/dto.kt`、`ReportService.kt` | ComplianceSummary + checklistVersionId |
| 12.3 | `module-report/.../application/ReportGenerationService.kt` | 快照生成/列表/详情/导出 |
| 12.3 | `module-report/.../application/HtmlReportRenderer.kt` | HTML 导出渲染 |
| 12.3 | `module-report/.../api/ReportSnapshotController.kt` + `api/dto/ReportSnapshotDtos.kt` | 生成/查询/导出端点 |
| 12.3 | `module-report/src/test/.../application/ReportGenerationServiceTest.kt`、`HtmlReportRendererTest.kt`、`api/ReportSnapshotControllerTest.kt` | 生成逻辑 + 渲染 + 端点切片 |
| 12.4 | `app-server/src/test/.../report/M12ReportIntegrationTest.kt` | 端到端（Testcontainers + MockMvc 完整链） |

---
### Task 12.1: 数据层 — V13 迁移 + 实体 + 仓储

**Files:**
- Create: `app-server/src/main/resources/db/migration/V13__report_snapshot_template.sql`
- Create: `module-report/src/main/kotlin/com/example/compliance/report/domain/ReportTemplate.kt`
- Create: `module-report/src/main/kotlin/com/example/compliance/report/domain/ReportTemplateVersion.kt`
- Create: `module-report/src/main/kotlin/com/example/compliance/report/domain/ReportSnapshot.kt`
- Create: `module-report/src/main/kotlin/com/example/compliance/report/infrastructure/ReportTemplateRepository.kt`
- Create: `module-report/src/main/kotlin/com/example/compliance/report/infrastructure/ReportTemplateVersionRepository.kt`
- Create: `module-report/src/main/kotlin/com/example/compliance/report/infrastructure/ReportSnapshotRepository.kt`
- Test: `app-server/src/test/kotlin/com/example/compliance/report/ReportRepositoryIntegrationTest.kt`

**Interfaces:**
- Consumes: `BaseEntity`（module-common，id/createdAt/updatedAt）；`VersionStatus`（module-checklist.domain）；`@JdbcTypeCode`（org.hibernate.annotations / SqlTypes）。
- Produces: `ReportTemplate`（templateType/name/description/version）、`ReportTemplateVersion`（templateId/versionNo:Int/status/sections:String/jsonb/createdBy/version）、`ReportSnapshot`（templateId/templateVersionNo/projectId?/scanTaskId?/checklistVersionId?/snapshotType/payload:String/jsonb/generatedBy?/generatedAt）；仓储方法签名（Task 12.2/12.3 消费）。

- [ ] **Step 1: 写失败测试**（`ReportRepositoryIntegrationTest.kt`）

```kotlin
package com.example.compliance.report

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.report.domain.ReportSnapshot
import com.example.compliance.report.domain.ReportTemplate
import com.example.compliance.report.domain.ReportTemplateVersion
import com.example.compliance.report.infrastructure.ReportSnapshotRepository
import com.example.compliance.report.infrastructure.ReportTemplateRepository
import com.example.compliance.report.infrastructure.ReportTemplateVersionRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** M12 数据层：jsonb 绑定（Ruling #13/#25 地雷）+ 版本/快照查询。数据前缀 M12R-*（与集成测试真实类型隔离）。 */
class ReportRepositoryIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var templateRepository: ReportTemplateRepository
    @Autowired lateinit var versionRepository: ReportTemplateVersionRepository
    @Autowired lateinit var snapshotRepository: ReportSnapshotRepository

    @Test
    fun `template version sections jsonb roundtrip and published lookup`() {
        val template = templateRepository.save(ReportTemplate().apply {
            templateType = "M12R-SCAN"; name = "scan report"
        })
        val sections = """{"sections":[{"title":"Summary"},{"title":"By severity"}]}"""
        versionRepository.save(ReportTemplateVersion().apply {
            this.templateId = template.id!!; versionNo = 1; status = VersionStatus.PUBLISHED
            this.sections = sections
        })
        versionRepository.save(ReportTemplateVersion().apply {
            this.templateId = template.id!!; versionNo = 2; status = VersionStatus.DRAFT
            this.sections = """{"sections":[{"title":"Draft section"}]}"""
        })

        // jsonb String 往返（无 @JdbcTypeCode 会在 INSERT 失败 — Ruling #13/#25）
        val loaded = versionRepository.findByTemplateIdOrderByVersionNoDesc(template.id!!)
        assertEquals(2, loaded.size)
        assertEquals(2, loaded[0].versionNo)
        assertTrue(loaded[1].sections.contains("Summary"))
        // PUBLISHED 最新版查询（生成只取 PUBLISHED）
        val published = versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(template.id!!, VersionStatus.PUBLISHED)
        assertNotNull(published)
        assertEquals(1, published.versionNo)
    }

    @Test
    fun `snapshot payload jsonb roundtrip and project listing`() {
        val template = templateRepository.save(ReportTemplate().apply {
            templateType = "M12R-COMPLIANCE"; name = "compliance report"
        })
        val payload = """{"score":80.00,"totalItems":10,"failed":2}"""
        snapshotRepository.save(ReportSnapshot().apply {
            this.templateId = template.id!!; templateVersionNo = 1
            projectId = 700001L; snapshotType = "COMPLIANCE"; this.payload = payload
            generatedAt = Instant.now()
        })
        val rows = snapshotRepository.findByProjectIdOrderByIdDesc(700001L)
        assertEquals(1, rows.size)
        assertTrue(rows[0].payload.contains("80.00"))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app-server:test --tests "com.example.compliance.report.ReportRepositoryIntegrationTest"`
Expected: FAIL（编译失败，实体/仓储不存在）。

- [ ] **Step 3: 实现 DDL**

`app-server/src/main/resources/db/migration/V13__report_snapshot_template.sql`：

```sql
-- M12: 报告模板（版本化，镜像 checklist_version 先例）+ 报告快照（不可变）
CREATE TABLE report_template (
    id            BIGSERIAL PRIMARY KEY,
    template_type VARCHAR(32)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   TEXT,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_report_template_type ON report_template(template_type);

CREATE TABLE report_template_version (
    id          BIGSERIAL PRIMARY KEY,
    template_id BIGINT       NOT NULL,
    version_no  INT          NOT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    sections    JSONB        NOT NULL,
    created_by  BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (template_id, version_no)
);
CREATE INDEX idx_rtv_template ON report_template_version (template_id, version_no);

CREATE TABLE report_snapshot (
    id                  BIGSERIAL PRIMARY KEY,
    template_id         BIGINT       NOT NULL REFERENCES report_template(id),
    template_version_no INT          NOT NULL,
    project_id          BIGINT,
    scan_task_id        BIGINT,
    checklist_version_id BIGINT,
    snapshot_type       VARCHAR(32)  NOT NULL,
    payload             JSONB        NOT NULL,
    generated_by        BIGINT,
    generated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_report_snapshot_project ON report_snapshot(project_id);
CREATE INDEX idx_report_snapshot_task ON report_snapshot(scan_task_id);
```

> 与 spec §3.1 DDL 的两处计划裁决（plan 层收紧，spec 兼容）：
> 1. `report_template.version` 与 `report_template_version.version` 用 `DEFAULT 0` + `@Version` 乐观锁，镜像 `checklist_version`（spec 写 DEFAULT 1，为镜像先例统一为 0——Hibernate 接管该列）。
> 2. `report_snapshot` 补 `created_at/updated_at`（BaseEntity 映射要求，符合表约定 #4）。

- [ ] **Step 4: 实现实体**

`module-report/.../domain/ReportTemplate.kt`：

```kotlin
package com.example.compliance.report.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Version

/** 报告模板主线：每类型一条（SCAN_SUMMARY/COMPLIANCE/TREND），版本历史在 ReportTemplateVersion。 */
@Entity
@Table(name = "report_template")
class ReportTemplate : BaseEntity() {
    @Column(name = "template_type", nullable = false, length = 32)
    lateinit var templateType: String
    @Column(name = "name", nullable = false, length = 128)
    lateinit var name: String
    @Column(name = "description")
    var description: String? = null
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
```

`module-report/.../domain/ReportTemplateVersion.kt`：

```kotlin
package com.example.compliance.report.domain

import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/** 报告模板版本：DRAFT→PUBLISHED→DISABLED。sections 为 JSONB（Ruling #13/#25 必须 @JdbcTypeCode）。 */
@Entity
@Table(name = "report_template_version")
class ReportTemplateVersion : BaseEntity() {
    @Column(name = "template_id", nullable = false)
    var templateId: Long = 0
    @Column(name = "version_no", nullable = false)
    var versionNo: Int = 0
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: VersionStatus = VersionStatus.DRAFT
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sections", columnDefinition = "jsonb")
    lateinit var sections: String
    @Column(name = "created_by")
    var createdBy: Long? = null
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
```

`module-report/.../domain/ReportSnapshot.kt`：

```kotlin
package com.example.compliance.report.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** 报告快照：生成时固化指标数据（payload JSONB），只增不改不删。 */
@Entity
@Table(name = "report_snapshot")
class ReportSnapshot : BaseEntity() {
    @Column(name = "template_id", nullable = false)
    var templateId: Long = 0
    @Column(name = "template_version_no", nullable = false)
    var templateVersionNo: Int = 0
    @Column(name = "project_id")
    var projectId: Long? = null
    @Column(name = "scan_task_id")
    var scanTaskId: Long? = null
    @Column(name = "checklist_version_id")
    var checklistVersionId: Long? = null
    @Column(name = "snapshot_type", nullable = false, length = 32)
    lateinit var snapshotType: String
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    lateinit var payload: String
    @Column(name = "generated_by")
    var generatedBy: Long? = null
    @Column(name = "generated_at", nullable = false)
    lateinit var generatedAt: Instant
}
```

- [ ] **Step 5: 实现仓储**

`module-report/.../infrastructure/ReportTemplateRepository.kt`：

```kotlin
package com.example.compliance.report.infrastructure

import com.example.compliance.report.domain.ReportTemplate
import org.springframework.data.jpa.repository.JpaRepository

interface ReportTemplateRepository : JpaRepository<ReportTemplate, Long> {
    fun findByTemplateType(templateType: String): ReportTemplate?
}
```

`module-report/.../infrastructure/ReportTemplateVersionRepository.kt`：

```kotlin
package com.example.compliance.report.infrastructure

import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.report.domain.ReportTemplateVersion
import org.springframework.data.jpa.repository.JpaRepository

interface ReportTemplateVersionRepository : JpaRepository<ReportTemplateVersion, Long> {
    fun findByTemplateIdOrderByVersionNoDesc(templateId: Long): List<ReportTemplateVersion>
    fun findFirstByTemplateIdAndStatusOrderByIdDesc(templateId: Long, status: VersionStatus): ReportTemplateVersion?
}
```

`module-report/.../infrastructure/ReportSnapshotRepository.kt`：

```kotlin
package com.example.compliance.report.infrastructure

import com.example.compliance.report.domain.ReportSnapshot
import org.springframework.data.jpa.repository.JpaRepository

interface ReportSnapshotRepository : JpaRepository<ReportSnapshot, Long> {
    fun findByProjectIdOrderByIdDesc(projectId: Long): List<ReportSnapshot>
    fun findBySnapshotTypeOrderByIdDesc(snapshotType: String): List<ReportSnapshot>
}
```

- [ ] **Step 6: 运行确认通过**

Run: `./gradlew :app-server:test --tests "com.example.compliance.report.ReportRepositoryIntegrationTest"`
Expected: PASS（jsonb 往返成功 → Ruling #13/#25 地雷已避开；两条查询断言通过）。同时确认 `./gradlew :app-server:classes` 通过（Flyway V13 在 `ddl-auto: validate` 下无实体/列不符）。

- [ ] **Step 7: 提交**

```bash
git add app-server/src/main/resources/db/migration/V13__report_snapshot_template.sql \
  module-report/src/main/kotlin/com/example/compliance/report/domain/ReportTemplate.kt \
  module-report/src/main/kotlin/com/example/compliance/report/domain/ReportTemplateVersion.kt \
  module-report/src/main/kotlin/com/example/compliance/report/domain/ReportSnapshot.kt \
  module-report/src/main/kotlin/com/example/compliance/report/infrastructure/ReportTemplateRepository.kt \
  module-report/src/main/kotlin/com/example/compliance/report/infrastructure/ReportTemplateVersionRepository.kt \
  module-report/src/main/kotlin/com/example/compliance/report/infrastructure/ReportSnapshotRepository.kt \
  app-server/src/test/kotlin/com/example/compliance/report/ReportRepositoryIntegrationTest.kt
git commit -m "feat(report): report template/snapshot data layer with V13 migration and jsonb entities (m12)"
```

---
### Task 12.2: 模板版本管理 — service + controller + RBAC

**Files:**
- Create: `module-report/src/main/kotlin/com/example/compliance/report/application/ReportTemplateService.kt`
- Create: `module-report/src/main/kotlin/com/example/compliance/report/api/ReportTemplateController.kt`
- Create: `module-report/src/main/kotlin/com/example/compliance/report/api/dto/ReportTemplateDtos.kt`
- Modify: `module-auth/src/main/kotlin/com/example/compliance/auth/config/SecurityConfig.kt:46`（在 `anyRequest().authenticated()` 之前插入 templates matcher）
- Modify: `module-report/build.gradle.kts`（加 jackson-module-kotlin testImplementation）
- Test: `module-report/src/test/kotlin/com/example/compliance/report/application/ReportTemplateServiceTest.kt`
- Test: `module-report/src/test/kotlin/com/example/compliance/report/api/ReportTemplateControllerTest.kt`

**Interfaces:**
- Consumes: Task 12.1 的 `ReportTemplateRepository.findByTemplateType` / `ReportTemplateVersionRepository.findByTemplateIdOrderByVersionNoDesc`、`findFirstByTemplateIdAndStatusOrderByIdDesc`；`AuditService`（module-common.audit）。
- Produces: `ReportTemplateService.draft(type, name?, sections: JsonNode): ReportTemplateVersion`、`publish(type): ReportTemplateVersion`、`disable(type): ReportTemplateVersion`、`versions(type): List<ReportTemplateVersion>`；`ReportTemplateController`（`/api/v1/reports/templates/{type}/draft|publish|disable|versions`）；`DraftRequest(name?, sections)`、`TemplateVersionView(templateId, versionNo, status, sections)`。SecurityConfig 三条有序 matcher（spec §3.3 三档：disable=ADMIN / versions=+AUDITOR / draft|publish=ADMIN,CM）。

- [ ] **Step 1: 写失败测试**（`ReportTemplateServiceTest.kt`）

```kotlin
package com.example.compliance.report.application

import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.common.audit.AuditService
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.report.domain.ReportTemplate
import com.example.compliance.report.domain.ReportTemplateVersion
import com.example.compliance.report.infrastructure.ReportTemplateRepository
import com.example.compliance.report.infrastructure.ReportTemplateVersionRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.firstArg
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportTemplateServiceTest {
    private val templateRepository = mockk<ReportTemplateRepository>(relaxed = true)
    private val versionRepository = mockk<ReportTemplateVersionRepository>(relaxed = true)
    private val auditService = mockk<AuditService>(relaxed = true)
    private val service = ReportTemplateService(templateRepository, versionRepository, auditService)
    private val mapper = ObjectMapper()

    private fun sections(s: String): JsonNode = mapper.readTree(s)

    @Test
    fun `first draft creates template line and version V1`() {
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns null
        // save 桩必须给模板赋 id —— service 用 template.id!! 进 currentDraftOrNew，不设会 NPE
        every { templateRepository.save(any()) } answers { firstArg<ReportTemplate>().also { it.id = 42L } }
        every { versionRepository.save(any()) } answers { firstArg() }
        val version = service.draft("SCAN_SUMMARY", "scan report", sections("""{"sections":[{"title":"Summary"}]}"""))

        verify { templateRepository.save(any()) }
        assertEquals(1, version.versionNo)
        assertEquals(VersionStatus.DRAFT, version.status)
        assertTrue(version.sections.contains("Summary"))
    }

    @Test
    fun `redraft updates existing draft version instead of opening new one`() {
        val template = ReportTemplate().apply { id = 1L; templateType = "COMPLIANCE"; name = "c" }
        val draft = ReportTemplateVersion().apply { id = 5L; templateId = 1L; versionNo = 1; status = VersionStatus.DRAFT; sections = "{}" }
        every { templateRepository.findByTemplateType("COMPLIANCE") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.DRAFT) } returns draft
        every { versionRepository.save(any()) } answers { firstArg() }

        val updated = service.draft("COMPLIANCE", null, sections("""{"sections":[{"title":"New"}]}"""))
        assertEquals(1, updated.versionNo)
        assertTrue(updated.sections.contains("New"))
    }

    @Test
    fun `draft after publish opens next version`() {
        val template = ReportTemplate().apply { id = 1L; templateType = "TREND"; name = "t" }
        val published = ReportTemplateVersion().apply { id = 9L; templateId = 1L; versionNo = 1; status = VersionStatus.PUBLISHED; sections = "{}" }
        every { templateRepository.findByTemplateType("TREND") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.DRAFT) } returns null
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(published)
        every { versionRepository.save(any()) } answers { firstArg() }

        val version = service.draft("TREND", null, sections("{}"))
        assertEquals(2, version.versionNo)
        assertEquals(VersionStatus.DRAFT, version.status)
    }

    @Test
    fun `publish requires an existing draft and records audit with valid json detail`() {
        val template = ReportTemplate().apply { id = 1L; templateType = "SCAN_SUMMARY"; name = "s" }
        val draft = ReportTemplateVersion().apply { id = 5L; templateId = 1L; versionNo = 1; status = VersionStatus.DRAFT; sections = "{}" }
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.DRAFT) } returns draft
        every { versionRepository.save(any()) } answers { firstArg() }

        val published = service.publish("SCAN_SUMMARY")
        assertEquals(VersionStatus.PUBLISHED, published.status)
        // Ruling #34: audit detail 必须是合法 JSON
        verify { auditService.record("REPORT_TEMPLATE_PUBLISHED", "report_template", 1L, "report_template_version", 5L, any()) }
    }

    @Test
    fun `publish without draft throws 400`() {
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns
            ReportTemplate().apply { id = 1L; templateType = "SCAN_SUMMARY"; name = "s" }
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.DRAFT) } returns null
        val e = assertFailsWith<BusinessException> { service.publish("SCAN_SUMMARY") }
        assertEquals(400, e.code)
    }

    @Test
    fun `disable marks latest published version disabled`() {
        val template = ReportTemplate().apply { id = 1L; templateType = "SCAN_SUMMARY"; name = "s" }
        val published = ReportTemplateVersion().apply { id = 5L; templateId = 1L; versionNo = 2; status = VersionStatus.PUBLISHED; sections = "{}" }
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns template
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(published)
        every { versionRepository.save(any()) } answers { firstArg() }

        val disabled = service.disable("SCAN_SUMMARY")
        assertEquals(VersionStatus.DISABLED, disabled.status)
    }

    @Test
    fun `disable ignores open draft and targets active published version`() {
        // R-M12-6: 打开中的 DRAFT v3 在顶部，disable 必须无视之并停用活跃 PUBLISHED v2
        val template = ReportTemplate().apply { id = 1L; templateType = "SCAN_SUMMARY"; name = "s" }
        val draft = ReportTemplateVersion().apply { id = 9L; templateId = 1L; versionNo = 3; status = VersionStatus.DRAFT; sections = "{}" }
        val published = ReportTemplateVersion().apply { id = 8L; templateId = 1L; versionNo = 2; status = VersionStatus.PUBLISHED; sections = "{}" }
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns template
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(draft, published)
        every { versionRepository.save(any()) } answers { firstArg() }

        val disabled = service.disable("SCAN_SUMMARY")
        assertEquals(VersionStatus.DISABLED, disabled.status)
        assertEquals(2, disabled.versionNo)   // 目标是 PUBLISHED v2，不是 DRAFT v3
    }

    @Test
    fun `disable without published version throws 400`() {
        // R-M12-6: 仅 DRAFT（从未发布）→ 无活跃 PUBLISHED 可停用 → 400
        val template = ReportTemplate().apply { id = 1L; templateType = "SCAN_SUMMARY"; name = "s" }
        val draft = ReportTemplateVersion().apply { id = 9L; templateId = 1L; versionNo = 1; status = VersionStatus.DRAFT; sections = "{}" }
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns template
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(draft)
        val e = assertFailsWith<BusinessException> { service.disable("SCAN_SUMMARY") }
        assertEquals(400, e.code)
    }

    @Test
    fun `versions lists desc`() {
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns
            ReportTemplate().apply { id = 1L; templateType = "SCAN_SUMMARY"; name = "s" }
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns
            listOf(
                ReportTemplateVersion().apply { id = 9L; templateId = 1L; versionNo = 2; status = VersionStatus.DRAFT; sections = "{}" },
                ReportTemplateVersion().apply { id = 8L; templateId = 1L; versionNo = 1; status = VersionStatus.PUBLISHED; sections = "{}" },
            )
        val versions = service.versions("SCAN_SUMMARY")
        assertEquals(2, versions[0].versionNo)
        assertEquals(1, versions[1].versionNo)
    }

    @Test
    fun `draft with unknown type rejects`() {
        assertFailsWith<BusinessException> {
            service.draft("NOT_A_TYPE", null, sections("{}"))
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-report:test --tests "com.example.compliance.report.application.ReportTemplateServiceTest"`
Expected: FAIL（编译失败，ReportTemplateService 不存在）。

- [ ] **Step 3: 实现 service**

`module-report/.../application/ReportTemplateService.kt`：

```kotlin
package com.example.compliance.report.application

import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.common.audit.AuditService
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.report.domain.ReportTemplate
import com.example.compliance.report.domain.ReportTemplateVersion
import com.example.compliance.report.infrastructure.ReportTemplateRepository
import com.example.compliance.report.infrastructure.ReportTemplateVersionRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 报告模板版本管理（镜像 ChecklistService.currentDraftOrNew/publish 先例）：DRAFT 编辑 → PUBLISH 生效 → DISABLE 停用。 */
@Service
class ReportTemplateService(
    private val templateRepository: ReportTemplateRepository,
    private val versionRepository: ReportTemplateVersionRepository,
    private val auditService: AuditService,
) {
    private val objectMapper = ObjectMapper()

    companion object {
        /** 支持的报告类型（生成/模板共用同一校验集）。 */
        val REPORT_TYPES = setOf("SCAN_SUMMARY", "COMPLIANCE", "TREND")
    }

    private fun requireType(type: String) {
        if (type !in REPORT_TYPES) throw BusinessException(400, "unsupported report type: $type")
    }

    @Transactional
    fun draft(type: String, name: String?, sections: JsonNode): ReportTemplateVersion {
        requireType(type)
        val template = templateRepository.findByTemplateType(type)
            ?: templateRepository.save(ReportTemplate().apply {
                this.templateType = type
                this.name = name ?: type.lowercase()
            })
        if (name != null) template.name = name
        return currentDraftOrNew(template.id!!, sections)
    }

    @Transactional
    fun publish(type: String): ReportTemplateVersion {
        requireType(type)
        val template = templateRepository.findByTemplateType(type)
            ?: throw BusinessException(404, "no report template for type: $type")
        val version = versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(template.id!!, VersionStatus.DRAFT)
            ?: throw BusinessException(400, "no draft report template version to publish for: $type")
        version.status = VersionStatus.PUBLISHED
        val saved = versionRepository.save(version)
        // Ruling #34: audit_log.detail 是 JSONB，detail 必须传合法 JSON
        auditService.record(
            "REPORT_TEMPLATE_PUBLISHED", "report_template", 1L, "report_template_version",
            saved.id, objectMapper.writeValueAsString(mapOf("type" to type, "versionNo" to saved.versionNo)),
        )
        return saved
    }

    @Transactional
    fun disable(type: String): ReportTemplateVersion {
        requireType(type)
        val template = templateRepository.findByTemplateType(type)
            ?: throw BusinessException(404, "no report template for type: $type")
        val versions = versionRepository.findByTemplateIdOrderByVersionNoDesc(template.id!!)
        // R-M12-6: 停用当前活跃的 PUBLISHED 版（spec §3.2 线性状态机 DRAFT→PUBLISHED→DISABLED，
        // "生成只取 PUBLISHED"）。最新版若为打开中的 DRAFT 则无视之——DRAFT 从不参与生成，
        // 停掉 DRAFT 只会让活跃 PUBLISHED 继续被生成使用，违背「停用」意图。
        val active = versions.firstOrNull { it.status == VersionStatus.PUBLISHED }
            ?: throw BusinessException(400, "no published report template version to disable for: $type")
        active.status = VersionStatus.DISABLED
        return versionRepository.save(active)
    }

    @Transactional(readOnly = true)
    fun versions(type: String): List<ReportTemplateVersion> {
        requireType(type)
        val template = templateRepository.findByTemplateType(type)
            ?: throw BusinessException(404, "no report template for type: $type")
        return versionRepository.findByTemplateIdOrderByVersionNoDesc(template.id!!)
    }

    private fun currentDraftOrNew(templateId: Long, sections: JsonNode): ReportTemplateVersion {
        versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(templateId, VersionStatus.DRAFT)
            ?.let { draft ->
                draft.sections = objectMapper.writeValueAsString(sections)
                return versionRepository.save(draft)
            }
        val latest = versionRepository.findByTemplateIdOrderByVersionNoDesc(templateId).firstOrNull()
        val nextNo = (latest?.versionNo ?: 0) + 1
        return versionRepository.save(ReportTemplateVersion().apply {
            this.templateId = templateId
            versionNo = nextNo
            status = VersionStatus.DRAFT
            this.sections = objectMapper.writeValueAsString(sections)
        })
    }
}
```

> **MockK strict 提示**（Global Constraints 未列但为仓库既有硬约束）：`ReportTemplateServiceTest` 中 `every { templateRepository.save(any()) } answers { firstArg() }` 等桩是必需的——非 relaxed mock 未 stub 即调会抛 MockKException。上方测试已全部覆盖（`mockk(relaxed=true)` 用于 auditService/templateRepository 的属性访问路径，但 `findByTemplateType`/`save` 等显式桩必须在需要返回值区分时给）。

- [ ] **Step 4: 实现 DTO + controller**

`module-report/.../api/dto/ReportTemplateDtos.kt`：

```kotlin
package com.example.compliance.report.api.dto

import com.example.compliance.report.domain.ReportTemplateVersion
import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.constraints.NotNull

data class DraftRequest(
    val name: String? = null,
    @field:NotNull
    val sections: JsonNode? = null,
)

data class TemplateVersionView(
    val templateId: Long,
    val versionNo: Int,
    val status: String,
    val sections: String,
) {
    companion object {
        fun from(v: ReportTemplateVersion) = TemplateVersionView(v.templateId, v.versionNo, v.status.name, v.sections)
    }
}
```

`module-report/.../api/ReportTemplateController.kt`：

```kotlin
package com.example.compliance.report.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.report.api.dto.DraftRequest
import com.example.compliance.report.api.dto.TemplateVersionView
import com.example.compliance.report.application.ReportTemplateService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

/** 报告模板管理（仅 ADMIN/COMPLIANCE_MANAGER，SecurityConfig 路径门控）。 */
@RestController
@RequestMapping("/api/v1/reports")
class ReportTemplateController(private val service: ReportTemplateService) {

    @PostMapping("/templates/{type}/draft")
    fun draft(@PathVariable type: String, @Valid @RequestBody req: DraftRequest): ApiResponse<TemplateVersionView> =
        ApiResponse.ok(TemplateVersionView.from(service.draft(type, req.name, req.sections!!)))

    @PostMapping("/templates/{type}/publish")
    fun publish(@PathVariable type: String): ApiResponse<TemplateVersionView> =
        ApiResponse.ok(TemplateVersionView.from(service.publish(type)))

    @PostMapping("/templates/{type}/disable")
    fun disable(@PathVariable type: String): ApiResponse<TemplateVersionView> =
        ApiResponse.ok(TemplateVersionView.from(service.disable(type)))

    @GetMapping("/templates/{type}/versions")
    fun versions(@PathVariable type: String): ApiResponse<List<TemplateVersionView>> =
        ApiResponse.ok(service.versions(type).map { TemplateVersionView.from(it) })
}
```

- [ ] **Step 5: SecurityConfig + build.gradle**

`module-auth/.../config/SecurityConfig.kt`：在 `auth.requestMatchers("/api/v1/openapi/tokens/**").hasRole("ADMIN")` 之后、`auth.anyRequest().authenticated()` 之前插入。**顺序即优先级**——disable 规则必须先命中，否则 CM 会从 general 规则穿过（首匹配生效，Spring 非「最具体优先」）：

```kotlin
                // M12 RBAC（spec §3.3 三档，顺序敏感）：disable 仅 ADMIN；versions 可 AUDITOR；draft/publish 走 general
                auth.requestMatchers("/api/v1/reports/templates/*/disable").hasRole("ADMIN")
                auth.requestMatchers("/api/v1/reports/templates/*/versions")
                    .hasAnyRole("ADMIN", "COMPLIANCE_MANAGER", "AUDITOR")
                auth.requestMatchers("/api/v1/reports/templates/**")
                    .hasAnyRole("ADMIN", "COMPLIANCE_MANAGER")
```

`module-report/build.gradle.kts`（镜像 module-remediation 先例）：

```kotlin
plugins { id("compliance-kotlin-module") }

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-scan"))
    implementation(project(":module-result"))
    implementation(project(":module-checklist"))
    // @WebMvcTest 切片把 JSON 反序列化为 Kotlin data class @RequestBody DTO，需要 jackson-module-kotlin
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}
```

- [ ] **Step 6: 写 controller 切片测试**（`ReportTemplateControllerTest.kt`）

```kotlin
package com.example.compliance.report.api

import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.report.api.dto.DraftRequest
import com.example.compliance.report.application.ReportTemplateService
import com.example.compliance.report.domain.ReportTemplateVersion
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** M12：报告模板端点切片（Security 过滤链关闭；RBAC 负例在 Task 12.4 集成测试走完整链）。 */
@WebMvcTest(ReportTemplateController::class)
@AutoConfigureMockMvc(addFilters = false)
class ReportTemplateControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var service: ReportTemplateService

    @TestConfiguration
    class TplServiceConfig {
        @Bean
        fun reportTemplateService(): ReportTemplateService = mockk()
    }

    private fun version() = ReportTemplateVersion().apply {
        id = 5L; templateId = 1L; versionNo = 1; status = VersionStatus.DRAFT
        sections = """{"sections":[{"title":"Summary"}]}"""
    }

    @Test
    fun `draft returns version view`() {
        every { service.draft("SCAN_SUMMARY", "scan report", any()) } returns version()
        mockMvc.perform(
            post("/api/v1/reports/templates/SCAN_SUMMARY/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"scan report","sections":{"sections":[{"title":"Summary"}]}}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.versionNo").value(1))
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
    }

    @Test
    fun `publish returns published version`() {
        val published = version().apply { status = VersionStatus.PUBLISHED }
        every { service.publish("SCAN_SUMMARY") } returns published
        mockMvc.perform(post("/api/v1/reports/templates/SCAN_SUMMARY/publish"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
    }

    @Test
    fun `versions lists versions`() {
        every { service.versions("SCAN_SUMMARY") } returns listOf(version())
        mockMvc.perform(get("/api/v1/reports/templates/SCAN_SUMMARY/versions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].versionNo").value(1))
    }

    @Test
    fun `disable returns disabled version`() {
        val disabled = version().apply { status = VersionStatus.DISABLED }
        every { service.disable("SCAN_SUMMARY") } returns disabled
        mockMvc.perform(post("/api/v1/reports/templates/SCAN_SUMMARY/disable"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("DISABLED"))
    }
}
```

- [ ] **Step 7: 运行确认通过**

Run: `./gradlew :module-report:test`
Expected: PASS（7 个 service 测试 + 4 个 controller 切片测试）。随后 `./gradlew :module-auth:compileKotlin :module-report:compileTestKotlin` 无错（SecurityConfig 修改编译通过）。

- [ ] **Step 8: 提交**

```bash
git add module-report/src/main/kotlin/com/example/compliance/report/application/ReportTemplateService.kt \
  module-report/src/main/kotlin/com/example/compliance/report/api/ReportTemplateController.kt \
  module-report/src/main/kotlin/com/example/compliance/report/api/dto/ReportTemplateDtos.kt \
  module-auth/src/main/kotlin/com/example/compliance/auth/config/SecurityConfig.kt \
  module-report/build.gradle.kts \
  module-report/src/test/kotlin/com/example/compliance/report/application/ReportTemplateServiceTest.kt \
  module-report/src/test/kotlin/com/example/compliance/report/api/ReportTemplateControllerTest.kt
git commit -m "feat(report): versioned report template management (draft/publish/disable) with RBAC (m12)"
```

---
### Task 12.3: 快照生成与查询 + 导出（JSON/HTML）

**Files:**
- Modify: `module-report/src/main/kotlin/com/example/compliance/report/application/dto.kt`（ComplianceSummary + checklistVersionId）
- Modify: `module-report/src/main/kotlin/com/example/compliance/report/application/ReportService.kt`（complianceSummary 填充 checklistVersionId）
- Create: `module-report/src/main/kotlin/com/example/compliance/report/application/ReportGenerationService.kt`
- Create: `module-report/src/main/kotlin/com/example/compliance/report/application/HtmlReportRenderer.kt`
- Create: `module-report/src/main/kotlin/com/example/compliance/report/api/ReportSnapshotController.kt`
- Create: `module-report/src/main/kotlin/com/example/compliance/report/api/dto/ReportSnapshotDtos.kt`
- Test: `module-report/src/test/kotlin/com/example/compliance/report/application/ReportGenerationServiceTest.kt`
- Test: `module-report/src/test/kotlin/com/example/compliance/report/application/HtmlReportRendererTest.kt`
- Test: `module-report/src/test/kotlin/com/example/compliance/report/api/ReportSnapshotControllerTest.kt`

**Interfaces:**
- Consumes: Task 12.1 仓储 + Task 12.2 `ReportTemplateService.REPORT_TYPES`（校验集）；既有 `ReportService.scanSummary(taskId)/complianceSummary(projectId)/trend(projectId, days)`（返回 ScanSummary/ComplianceSummary/TrendPoint）。
- Produces: `ReportGenerationService.generate(type, projectId?, scanTaskId?, generatedBy?): ReportSnapshot`、`list(projectId?, type?, page, size): Page<ReportSnapshot>`、`detail(id): ReportSnapshot`、`export(id, format): String`；`ReportSnapshotController`（`POST /api/v1/reports/{type}/generate`、`GET /api/v1/reports/snapshots`、`GET /api/v1/reports/snapshots/{id}`、`GET /api/v1/reports/snapshots/{id}/export?format=json|html`）；`GenerateRequest(projectId?, scanTaskId?)`、`SnapshotSummaryView`、`SnapshotView`。`ComplianceSummary` DTO 增加 `checklistVersionId: Long?`。

- [ ] **Step 1: 写失败测试**（`ReportGenerationServiceTest.kt` + `HtmlReportRendererTest.kt`）

`ReportGenerationServiceTest.kt`：

```kotlin
package com.example.compliance.report.application

import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.report.domain.ReportSnapshot
import com.example.compliance.report.domain.ReportTemplate
import com.example.compliance.report.domain.ReportTemplateVersion
import com.example.compliance.report.infrastructure.ReportSnapshotRepository
import com.example.compliance.report.infrastructure.ReportTemplateRepository
import com.example.compliance.report.infrastructure.ReportTemplateVersionRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportGenerationServiceTest {
    private val reportService = mockk<ReportService>(relaxed = true)
    private val templateRepository = mockk<ReportTemplateRepository>(relaxed = true)
    private val versionRepository = mockk<ReportTemplateVersionRepository>(relaxed = true)
    private val snapshotRepository = mockk<ReportSnapshotRepository>(relaxed = true)
    private val service = ReportGenerationService(reportService, templateRepository, versionRepository, snapshotRepository)

    private fun template(type: String) = ReportTemplate().apply { id = 1L; this.templateType = type; name = type.lowercase() }
    private fun published() = ReportTemplateVersion().apply {
        id = 5L; templateId = 1L; versionNo = 2; status = VersionStatus.PUBLISHED; sections = "{}"
    }

    @Test
    fun `scan summary generation snapshots payload with published template version`() {
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns template("SCAN_SUMMARY")
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.PUBLISHED) } returns published()
        every { reportService.scanSummary(77L) } returns ScanSummary(77L, "SEMGREP", "SUCCESS", 3, mapOf("HIGH" to 2, "MEDIUM" to 1))
        every { snapshotRepository.save(any()) } answers { firstArg() }

        val snapshot = service.generate("SCAN_SUMMARY", projectId = null, scanTaskId = 77L, generatedBy = 3L)
        assertEquals(2, snapshot.templateVersionNo)
        assertEquals(77L, snapshot.scanTaskId)
        assertNull(snapshot.projectId)
        assertEquals("SCAN_SUMMARY", snapshot.snapshotType)
        assertTrue(snapshot.payload.contains("findingCount"))
        assertTrue(snapshot.payload.contains("SEMGREP"))
    }

    @Test
    fun `compliance generation captures checklistVersionId and project`() {
        every { templateRepository.findByTemplateType("COMPLIANCE") } returns template("COMPLIANCE")
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.PUBLISHED) } returns published()
        every { reportService.complianceSummary(88L) } returns ComplianceSummary(
            88L, 6L, checklistVersionId = 4L, score = BigDecimal("80.00"), totalItems = 10,
            passed = 8, failed = 2, warning = 0, manual = 0, skipped = 0, items = emptyList(),
        )
        every { snapshotRepository.save(any()) } answers { firstArg() }

        val snapshot = service.generate("COMPLIANCE", projectId = 88L, scanTaskId = null, generatedBy = null)
        assertEquals(88L, snapshot.projectId)
        assertEquals(4L, snapshot.checklistVersionId)
        assertTrue(snapshot.payload.contains("80.00"))
    }

    @Test
    fun `trend generation requires project`() {
        every { templateRepository.findByTemplateType("TREND") } returns template("TREND")
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.PUBLISHED) } returns published()
        every { reportService.trend(88L, 30) } returns listOf(TrendPoint("2026-09-01T00:00:00Z", BigDecimal("80.00"), 2))
        every { snapshotRepository.save(any()) } answers { firstArg() }

        val snapshot = service.generate("TREND", projectId = 88L, scanTaskId = null, generatedBy = null)
        assertTrue(snapshot.payload.startsWith("["))        // TrendPoint 列表 → JSON 数组（序列化字段为 evaluatedAt/score/failed）
        assertTrue(snapshot.payload.contains("evaluatedAt"))
    }

    @Test
    fun `generate without published template throws 400`() {
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns template("SCAN_SUMMARY")
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.PUBLISHED) } returns null
        val e = assertFailsWith<BusinessException> { service.generate("SCAN_SUMMARY", null, 77L, null) }
        assertEquals(400, e.code)
    }

    @Test
    fun `scan summary requires scanTaskId and unknown type rejected`() {
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns template("SCAN_SUMMARY")
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.PUBLISHED) } returns published()
        val e = assertFailsWith<BusinessException> { service.generate("SCAN_SUMMARY", null, null, null) }
        assertEquals(400, e.code)
        assertFailsWith<BusinessException> { service.generate("NOT_A_TYPE", null, null, null) }
    }

    @Test
    fun `detail missing throws 404 and export formats resolve`() {
        every { snapshotRepository.findById(99L) } returns Optional.empty()
        assertFailsWith<BusinessException> { service.detail(99L) }

        val snapshot = ReportSnapshot().apply {
            id = 3L; templateId = 1L; templateVersionNo = 2; snapshotType = "SCAN_SUMMARY"
            payload = """{"findingCount":3,"engine":"SEMGREP"}"""; generatedAt = java.time.Instant.now()
        }
        every { snapshotRepository.findById(3L) } returns Optional.of(snapshot)
        assertEquals(snapshot.payload, service.export(3L, "json"))
        assertTrue(service.export(3L, "html").contains("findingCount"))
        assertFailsWith<BusinessException> { service.export(3L, "pdf") }
    }
}
```

`HtmlReportRendererTest.kt`：

```kotlin
package com.example.compliance.report.application

import com.example.compliance.report.domain.ReportSnapshot
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlReportRendererTest {
    @Test
    fun `render produces readable html with type id and payload values`() {
        val snapshot = ReportSnapshot().apply {
            id = 3L; templateId = 1L; templateVersionNo = 2; snapshotType = "SCAN_SUMMARY"
            payload = """{"findingCount":3,"engine":"SEMGREP","bySeverity":{"HIGH":2}}"""
            generatedAt = Instant.parse("2026-09-03T10:00:00Z")
        }
        val html = HtmlReportRenderer.render(snapshot)
        assertTrue(html.contains("SCAN_SUMMARY"))
        assertTrue(html.contains("Report #3"))
        assertTrue(html.contains("findingCount"))
        assertTrue(html.contains("3"))
        assertTrue(html.contains("template v2"))
        // 未转义注入不出现原始尖括号（escape 生效）
        assertFalse(html.contains("<script>"))
    }

    @Test
    fun `render escapes html in payload values`() {
        val snapshot = ReportSnapshot().apply {
            id = 4L; templateId = 1L; templateVersionNo = 1; snapshotType = "COMPLIANCE"
            payload = """{"message":"<script>alert(1)</script>"}"""
            generatedAt = Instant.now()
        }
        assertFalse(HtmlReportRenderer.render(snapshot).contains("<script>"))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-report:test --tests "com.example.compliance.report.application.ReportGenerationServiceTest" --tests "com.example.compliance.report.application.HtmlReportRendererTest"`
Expected: FAIL（编译失败，ReportGenerationService/HtmlReportRenderer/ComplianceSummary.checklistVersionId 不存在）。

- [ ] **Step 3: ComplianceSummary 增加 checklistVersionId**

`module-report/.../application/dto.kt` 的 ComplianceSummary：

```kotlin
data class ComplianceSummary(
    val projectId: Long,
    val evaluationId: Long,
    val score: BigDecimal?,
    val totalItems: Int,
    val passed: Int,
    val failed: Int,
    val warning: Int,
    val manual: Int,
    val skipped: Int,
    val items: List<ItemSummary>,
    val checklistVersionId: Long? = null,   // M12: 快照可追溯引用（spec P3-D3）
)
```

`module-report/.../application/ReportService.kt` 的 `complianceSummary` 构造补最后一个字段（命名参数，与 DTO 字段顺序一致）：

```kotlin
        return ComplianceSummary(
            projectId = projectId,
            evaluationId = evaluation.id!!,
            score = evaluation.score,
            totalItems = evaluation.totalItems,
            passed = evaluation.passed,
            failed = evaluation.failed,
            warning = evaluation.warning,
            manual = evaluation.manual,
            skipped = evaluation.skipped,
            items = items.map { ItemSummary(it.itemCode, it.result, it.findingCount) },
            checklistVersionId = evaluation.checklistVersionId,   // M12: 快照可追溯引用（spec P3-D3）
        )
```

> **破坏检查**：`checklistVersionId` 带默认值 `= null` → 既有 `ReportServiceTest.complianceSummary returns latest evaluation`（只读 service 返回值字段，不直接构造 DTO）不受影响；生成测试以命名参数 `checklistVersionId = 4L` 覆盖默认值。

- [ ] **Step 4: 实现 ReportGenerationService + HtmlReportRenderer**

`module-report/.../application/ReportGenerationService.kt`：

```kotlin
package com.example.compliance.report.application

import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.report.domain.ReportSnapshot
import com.example.compliance.report.infrastructure.ReportSnapshotRepository
import com.example.compliance.report.infrastructure.ReportTemplateRepository
import com.example.compliance.report.infrastructure.ReportTemplateVersionRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 报告快照生成与查询（spec P3-D3/P3-D4）：生成只取 PUBLISHED 最新模板版，payload 经 ReportService+ReportMetrics 统一口径后落 JSONB。 */
@Service
class ReportGenerationService(
    private val reportService: ReportService,
    private val templateRepository: ReportTemplateRepository,
    private val versionRepository: ReportTemplateVersionRepository,
    private val snapshotRepository: ReportSnapshotRepository,
) {
    private val objectMapper = ObjectMapper()

    @Transactional
    fun generate(type: String, projectId: Long?, scanTaskId: Long?, generatedBy: Long?): ReportSnapshot {
        if (type !in ReportTemplateService.REPORT_TYPES) throw BusinessException(400, "unsupported report type: $type")
        if (type == "SCAN_SUMMARY" && scanTaskId == null) throw BusinessException(400, "SCAN_SUMMARY requires scanTaskId")
        if (type != "SCAN_SUMMARY" && projectId == null) throw BusinessException(400, "$type requires projectId")

        val template = templateRepository.findByTemplateType(type)
            ?: throw BusinessException(404, "no report template for type: $type")
        val version = versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(template.id!!, VersionStatus.PUBLISHED)
            ?: throw BusinessException(400, "no published report template for type: $type")

        val checklistVersionId: Long?
        val payload: String
        when (type) {
            "SCAN_SUMMARY" -> {
                checklistVersionId = null
                payload = objectMapper.writeValueAsString(reportService.scanSummary(scanTaskId!!))
            }
            "COMPLIANCE" -> {
                val summary = reportService.complianceSummary(projectId!!)
                checklistVersionId = summary.checklistVersionId
                payload = objectMapper.writeValueAsString(summary)
            }
            else -> { // TREND
                checklistVersionId = null
                payload = objectMapper.writeValueAsString(reportService.trend(projectId!!, 30))
            }
        }
        return snapshotRepository.save(ReportSnapshot().apply {
            this.templateId = template.id!!
            templateVersionNo = version.versionNo
            this.projectId = projectId
            this.scanTaskId = scanTaskId
            this.checklistVersionId = checklistVersionId
            this.snapshotType = type
            this.payload = payload
            this.generatedBy = generatedBy
            this.generatedAt = Instant.now()
        })
    }

    @Transactional(readOnly = true)
    fun detail(id: Long): ReportSnapshot = snapshotRepository.findById(id)
        .orElseThrow { BusinessException(404, "report snapshot not found: $id") }

    @Transactional(readOnly = true)
    fun export(id: Long, format: String): String {
        val snapshot = detail(id)
        return when (format) {
            "json" -> snapshot.payload
            "html" -> HtmlReportRenderer.render(snapshot)
            else -> throw BusinessException(400, "unsupported export format: $format")
        }
    }

    // 列表：projectId/type 均为可选过滤，4 分支（快照列表页，无需 Specification——YAGNI）
    @Transactional(readOnly = true)
    fun list(projectId: Long?, type: String?, page: Int, size: Int): Page<ReportSnapshot> {
        val pageable = PageRequest.of(page, size)
        return when {
            projectId != null && type != null -> snapshotRepository.findByProjectIdAndSnapshotType(projectId, type, pageable)
            projectId != null -> snapshotRepository.findByProjectId(projectId, pageable)
            type != null -> snapshotRepository.findBySnapshotType(type, pageable)
            else -> snapshotRepository.findAll(pageable)
        }
    }
}
```

> **注意**：`ReportSnapshotRepository`（Task 12.1）需在 Step 4 一并补三个分页方法（Task 12.1 Step 5 只有两个非分页方法）——这是 plan 内跨任务接口补充，同文件追加：

```kotlin
    fun findByProjectId(projectId: Long, pageable: Pageable): Page<ReportSnapshot>
    fun findBySnapshotType(snapshotType: String, pageable: Pageable): Page<ReportSnapshot>
    fun findByProjectIdAndSnapshotType(projectId: Long, snapshotType: String, pageable: Pageable): Page<ReportSnapshot>
```

（补 import：`org.springframework.data.domain.Page`、`org.springframework.data.domain.Pageable`。）

`module-report/.../application/HtmlReportRenderer.kt`：

```kotlin
package com.example.compliance.report.application

import com.example.compliance.report.domain.ReportSnapshot
import com.fasterxml.jackson.databind.ObjectMapper

/** HTML 导出渲染：固定模板（不引入模板库），payload 顶层键值表 + 元数据头。值经 HTML 转义。 */
object HtmlReportRenderer {
    private val objectMapper = ObjectMapper()

    fun render(snapshot: ReportSnapshot): String {
        val root = objectMapper.readTree(snapshot.payload)
        val rows = root.fields().asSequence().joinToString("") { (k, v) ->
            "<tr><td>${escape(k)}</td><td>${escape(v.toString())}</td></tr>"
        }
        return """<html><head><title>Report #${snapshot.id} (${snapshot.snapshotType})</title></head>
<body><h1>${escape(snapshot.snapshotType)} report #${snapshot.id}</h1>
<p>template v${snapshot.templateVersionNo} &middot; generatedAt ${snapshot.generatedAt}</p>
<table border="1"><tr><th>key</th><th>value</th></tr>$rows</table></body></html>"""
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
```

- [ ] **Step 5: 实现 DTO + controller**

`module-report/.../api/dto/ReportSnapshotDtos.kt`：

```kotlin
package com.example.compliance.report.api.dto

import com.example.compliance.report.domain.ReportSnapshot
import java.time.Instant

data class GenerateRequest(
    val projectId: Long? = null,
    val scanTaskId: Long? = null,
)

/** 列表视图（不含 payload，保持列表轻量）。 */
data class SnapshotSummaryView(
    val id: Long,
    val templateVersionNo: Int,
    val projectId: Long?,
    val scanTaskId: Long?,
    val checklistVersionId: Long?,
    val snapshotType: String,
    val generatedAt: String,
) {
    companion object {
        fun from(s: ReportSnapshot) = SnapshotSummaryView(
            s.id!!, s.templateVersionNo, s.projectId, s.scanTaskId, s.checklistVersionId,
            s.snapshotType, s.generatedAt.toString(),
        )
    }
}

/** 详情视图（含 payload 原文，供展示/导出）。 */
data class SnapshotView(
    val id: Long,
    val templateVersionNo: Int,
    val projectId: Long?,
    val scanTaskId: Long?,
    val checklistVersionId: Long?,
    val snapshotType: String,
    val generatedAt: String,
    val payload: String,
) {
    companion object {
        fun from(s: ReportSnapshot) = SnapshotView(
            s.id!!, s.templateVersionNo, s.projectId, s.scanTaskId, s.checklistVersionId,
            s.snapshotType, s.generatedAt.toString(), s.payload,
        )
    }
}
```

`module-report/.../api/ReportSnapshotController.kt`：

```kotlin
package com.example.compliance.report.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.common.api.PageResponse
import com.example.compliance.common.auth.AuthPrincipal
import com.example.compliance.report.api.dto.GenerateRequest
import com.example.compliance.report.api.dto.SnapshotSummaryView
import com.example.compliance.report.api.dto.SnapshotView
import com.example.compliance.report.application.ReportGenerationService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

/** 报告快照生成/查询/导出（spec §3.3；认证用户即可，RBAC 见 SecurityConfig）。 */
@RestController
@RequestMapping("/api/v1/reports")
class ReportSnapshotController(private val generationService: ReportGenerationService) {

    @PostMapping("/{type}/generate")
    fun generate(
        @PathVariable type: String,
        @RequestBody(required = false) req: GenerateRequest?,
        authentication: Authentication?,
    ): ApiResponse<SnapshotView> {
        val actorId = (authentication?.principal as? AuthPrincipal)?.userId ?: 1L
        val snapshot = generationService.generate(type, req?.projectId, req?.scanTaskId, actorId)
        return ApiResponse.ok(SnapshotView.from(snapshot))
    }

    @GetMapping("/snapshots")
    fun list(
        @RequestParam(required = false) projectId: Long?,
        @RequestParam(required = false) type: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<SnapshotSummaryView>> {
        val result = generationService.list(projectId, type, page, size)
        return ApiResponse.ok(
            PageResponse(
                items = result.content.map { SnapshotSummaryView.from(it) },
                page = result.number,
                size = result.size,
                total = result.totalElements,
            )
        )
    }

    @GetMapping("/snapshots/{id}")
    fun detail(@PathVariable id: Long): ApiResponse<SnapshotView> =
        ApiResponse.ok(SnapshotView.from(generationService.detail(id)))

    @GetMapping("/snapshots/{id}/export")
    fun export(@PathVariable id: Long, @RequestParam(defaultValue = "json") format: String): ApiResponse<String> =
        ApiResponse.ok(generationService.export(id, format))
}
```

> `authentication?.principal` 回落 1L 与 RemediationController.actorId 先例一致（AuthPrincipal 注释）。

- [ ] **Step 6: 写 controller 切片测试**（`ReportSnapshotControllerTest.kt`）

```kotlin
package com.example.compliance.report.api

import com.example.compliance.report.application.ReportGenerationService
import com.example.compliance.report.domain.ReportSnapshot
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/** M12：快照生成/查询/导出端点切片。 */
@WebMvcTest(ReportSnapshotController::class)
@AutoConfigureMockMvc(addFilters = false)
class ReportSnapshotControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var generationService: ReportGenerationService

    @TestConfiguration
    class GenServiceConfig {
        @Bean
        fun reportGenerationService(): ReportGenerationService = mockk()
    }

    private fun snapshot() = ReportSnapshot().apply {
        id = 3L; templateId = 1L; templateVersionNo = 2; projectId = 88L
        snapshotType = "SCAN_SUMMARY"
        payload = """{"findingCount":3}"""; generatedAt = Instant.now()
    }

    @Test
    fun `generate returns snapshot view`() {
        every { generationService.generate("SCAN_SUMMARY", null, 77L, 1L) } returns snapshot()
        mockMvc.perform(
            post("/api/v1/reports/SCAN_SUMMARY/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"scanTaskId":77}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.snapshotType").value("SCAN_SUMMARY"))
            .andExpect(jsonPath("$.data.templateVersionNo").value(2))
    }

    @Test
    fun `list returns paged summaries`() {
        val page = org.springframework.data.domain.PageImpl(
            listOf(snapshot()),
            org.springframework.data.domain.PageRequest.of(0, 20), 1L,
        )
        every { generationService.list(null, null, 0, 20) } returns page
        mockMvc.perform(get("/api/v1/reports/snapshots"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(3))
    }

    @Test
    fun `detail and export return content`() {
        every { generationService.detail(3L) } returns snapshot()
        every { generationService.export(3L, "json") } returns """{"findingCount":3}"""
        mockMvc.perform(get("/api/v1/reports/snapshots/3"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.payload").value("""{"findingCount":3}"""))
        mockMvc.perform(get("/api/v1/reports/snapshots/3/export?format=json"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").value("""{"findingCount":3}"""))
    }
}
```

- [ ] **Step 7: 运行确认通过**

Run: `./gradlew :module-report:test`
Expected: PASS（12.2 既有 + 6 个 generation 测试 + 2 个 renderer 测试 + 3 个 controller 切片测试；既有 ReportServiceTest/ReportMetricsTest 仍绿——验证 ComplianceSummary 加字段无破坏）。随后 `./gradlew :app-server:classes` 通过（Flyway validate）。

- [ ] **Step 8: 提交**

```bash
git add module-report/src/main/kotlin/com/example/compliance/report/application/dto.kt \
  module-report/src/main/kotlin/com/example/compliance/report/application/ReportService.kt \
  module-report/src/main/kotlin/com/example/compliance/report/application/ReportGenerationService.kt \
  module-report/src/main/kotlin/com/example/compliance/report/application/HtmlReportRenderer.kt \
  module-report/src/main/kotlin/com/example/compliance/report/infrastructure/ReportSnapshotRepository.kt \
  module-report/src/main/kotlin/com/example/compliance/report/api/ReportSnapshotController.kt \
  module-report/src/main/kotlin/com/example/compliance/report/api/dto/ReportSnapshotDtos.kt \
  module-report/src/test/kotlin/com/example/compliance/report/application/ReportGenerationServiceTest.kt \
  module-report/src/test/kotlin/com/example/compliance/report/application/HtmlReportRendererTest.kt \
  module-report/src/test/kotlin/com/example/compliance/report/api/ReportSnapshotControllerTest.kt
git commit -m "feat(report): immutable report snapshots with template-driven generation, list, detail, export (m12)"
```

---
### Task 12.4: M12 端到端集成测试

**Files:**
- Create: `app-server/src/test/kotlin/com/example/compliance/report/M12ReportIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 12.2/12.3 全部端点 + SecurityConfig RBAC；共享 Testcontainers（`AbstractIntegrationTest`）；既有 AuthPrincipal/Ruling #49（`@WithMockUser`）。
- Produces: 无新接口——验证 M12 全链路与 RBAC 门控。

- [ ] **Step 1: 写失败测试**（`M12ReportIntegrationTest.kt`）

```kotlin
package com.example.compliance.report

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** M12 端到端：模板生命周期 + RBAC 三档 + TREND 快照生成/列表/详情/导出（真实 DB + Security 链）。
 *  数据前缀 M12-*：TREND 生成只需 project（新项目 trend=空列表 → payload "[]"，确定性、零扫描/评估依赖）；
 *  report_template 表仅本测试类写入（TREND/SCAN_SUMMARY 两类型，无跨类串扰）。 */
@AutoConfigureMockMvc
class M12ReportIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var projectService: ProjectService
    private val objectMapper = ObjectMapper()

    @Test
    fun `unauthenticated snapshot access is 401`() {
        mockMvc.perform(get("/api/v1/reports/snapshots")).andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(username = "m12-cm", roles = ["COMPLIANCE_MANAGER"])
    fun `template rbac tiers admin manager auditor developer`() {
        // CM 可 draft（general 规则）→ 200，建 SCAN_SUMMARY DRAFT（单方法内先建，供 AUDITOR versions 读）
        mockMvc.perform(post("/api/v1/reports/templates/SCAN_SUMMARY/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"M12 scan","sections":{"sections":[{"title":"Summary"}]}}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
        // CM 可 publish（general 规则）→ 200（R-M12-6：disable 仅停用活跃 PUBLISHED 版，先发布才有可停用对象）
        mockMvc.perform(post("/api/v1/reports/templates/SCAN_SUMMARY/publish"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
        // AUDITOR 可看 versions（spec §3.3：ADMIN/CM/AUDITOR）→ 200
        val auditor = SecurityMockMvcRequestPostProcessors.user("m12-auditor").roles("AUDITOR")
        mockMvc.perform(get("/api/v1/reports/templates/SCAN_SUMMARY/versions").with(auditor))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].status").value("PUBLISHED"))
        // AUDITOR / DEVELOPER 不能 draft → 403
        mockMvc.perform(post("/api/v1/reports/templates/SCAN_SUMMARY/draft").with(auditor)
                .contentType(MediaType.APPLICATION_JSON).content("""{"sections":{}}"""))
            .andExpect(status().isForbidden)
        val developer = SecurityMockMvcRequestPostProcessors.user("m12-dev").roles("DEVELOPER")
        mockMvc.perform(post("/api/v1/reports/templates/SCAN_SUMMARY/draft").with(developer)
                .contentType(MediaType.APPLICATION_JSON).content("""{"sections":{}}"""))
            .andExpect(status().isForbidden)
        // CM 不能 disable（disable 仅 ADMIN；*/disable 规则先于 general 命中）→ 403
        mockMvc.perform(post("/api/v1/reports/templates/SCAN_SUMMARY/disable").with(
            SecurityMockMvcRequestPostProcessors.user("m12-cm").roles("COMPLIANCE_MANAGER")))
            .andExpect(status().isForbidden)
        // ADMIN 可 disable → 200
        mockMvc.perform(post("/api/v1/reports/templates/SCAN_SUMMARY/disable").with(
            SecurityMockMvcRequestPostProcessors.user("m12-admin").roles("ADMIN")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("DISABLED"))
    }

    @Test
    @WithMockUser(username = "m12-admin", roles = ["ADMIN"])
    fun `admin template lifecycle then trend snapshot generate list detail export`() {
        val project = projectService.create(CreateProjectCommand("M12RP", "M12 report", null, null))

        // 1. TREND 模板 DRAFT → PUBLISH（生成只取 PUBLISHED 最新版）
        mockMvc.perform(post("/api/v1/reports/templates/TREND/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"M12 trend","sections":{"sections":[{"title":"Trend"}]}}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.versionNo").value(1))
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
        mockMvc.perform(post("/api/v1/reports/templates/TREND/publish"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))

        // 2. 生成 TREND 快照（新项目 trend=空列表 → payload "[]"，确定性）
        val createResponse = mockMvc.perform(post("/api/v1/reports/TREND/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"projectId":${project.id}}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.snapshotType").value("TREND"))
            .andExpect(jsonPath("$.data.templateVersionNo").value(1))
            .andExpect(jsonPath("$.data.projectId").value(project.id))
            .andReturn()
        val snapshotId = objectMapper.readTree(createResponse.response.contentAsString)["data"]["id"].asLong()

        // 3. 列表按 projectId 过滤含该快照（确定性 total=1）
        mockMvc.perform(get("/api/v1/reports/snapshots").param("projectId", project.id.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].snapshotType").value("TREND"))

        // 4. 详情回读 payload（不可变快照原文）
        mockMvc.perform(get("/api/v1/reports/snapshots/$snapshotId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.snapshotType").value("TREND"))
            .andExpect(jsonPath("$.data.payload").value("[]"))

        // 5. 导出 JSON 与详情 payload 一致
        mockMvc.perform(get("/api/v1/reports/snapshots/$snapshotId/export?format=json"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").value("[]"))
    }
}
```

> **确定性设计（防实现者踩坑）**：本测试**不依赖扫描/评估业务数据构造**——TREND 生成对全新项目返回空 trend 列表（payload `"[]"`），零串扰且断言确定（ReportService.trend 对无 evaluation 项目返回空列表，无 404）；RBAC 三档在单方法内用 `.with(SecurityMockMvcRequestPostProcessors.user(...).roles(...))` 逐请求覆盖角色，规避 JUnit 方法顺序不确定性（AUDITOR versions 需模板已存在，故先 CM draft+publish 再 AUDITOR 读，全在方法内自洽；publish 同时为 R-M12-6 disable 语义提供活跃 PUBLISHED 版）。快照不可变性由 **API 表面**保证（快照无 PUT/DELETE 端点，spec §3.4；只增不改不删）。未认证 401 与 ReportApiIntegrationTest/RbacIntegrationTest 同形态（spring-security-test 已在 app-server test classpath）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app-server:test --tests "com.example.compliance.report.M12ReportIntegrationTest"`
Expected: FAIL（编译失败或 RBAC 断言失败——SecurityConfig matcher 若未实现则 403 负例直接失败）。

- [ ] **Step 3: 实现（本任务以测试为主）**

本任务无生产代码（SecurityConfig 已在 12.2 完成）。实现者按 Step 1 修正指引把第一个测试方法改写为 (A) 或 (B) 的确定性断言，补齐实现，使全部断言通过。

- [ ] **Step 4: 全量回归**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（全部既有测试 + M12 新测试绿；共享容器 `max_connections=300` 下无连接耗尽）。

- [ ] **Step 5: 提交**

```bash
git add app-server/src/test/kotlin/com/example/compliance/report/M12ReportIntegrationTest.kt
git commit -m "test(app-server): M12 report template/snapshot end-to-end with RBAC gating (m12)"
```

---
## Self-Review（写完即执行）

**Spec 覆盖（spec §3.1–3.4 / §7）：**
- §3.1 三表 DDL → Task 12.1 ✅（含 FK、唯一索引、jsonb）
- §3.2 模板驱动生成（取 PUBLISHED 最新版 + 记模板版本号 + checklistVersionId 可追溯）→ Task 12.3 ✅
- §3.3 全部端点（模板 draft/publish/disable/versions + generate/list/detail/export json|html）→ Task 12.2/12.3/12.4 ✅；**RBAC 三档**（disable=ADMIN / versions=+AUDITOR / draft|publish=ADMIN,CM）→ SecurityConfig 三条有序 matcher（Task 12.2）+ Task 12.4 单方法三档断言 ✅
- §3.4 测试（版本状态机/生成口径一致/不可变/导出/RBAC）→ Task 12.2–12.4 ✅（不可变由 API 表面保证：快照无 PUT/DELETE 端点；V13 `ddl-auto: validate` 由 Task 12.1 集成测试 + Task 12.3 Step 7 验证）
- §7 约束逐条落位：P2-D5（VersionStatus 从 checklist 复用，module-report 已依赖，非 @Entity）✅；Ruling #34（audit JSON detail）✅；Ruling #13/#25（jsonb @JdbcTypeCode）✅ Task 12.1 核心；Ruling #49（@WithMockUser 用完整链集成测试；切片 addFilters=false）✅；数据前缀 M12-* ✅；快照不可变（无 update/delete 暴露）✅。

**占位扫描：** 全计划无 TBD/TODO/“类似 Task N”/仅描述无代码的步骤。Task 12.4 为确定性测试（TREND 空列表链路 + 单方法内 RBAC 三档逐请求覆盖），无修正指引残留。

**类型一致性：** `versionNo: Int`（12.1 实体 = 12.2 service 自增 = 12.3 快照引用）一致；`ReportTemplateService.REPORT_TYPES` 校验集被 12.3 generate 复用一致；仓储方法名跨任务逐字一致（`findFirstByTemplateIdAndStatusOrderByIdDesc`、`findByTemplateIdOrderByVersionNoDesc`、`findByProjectIdOrderByIdDesc`）；`ComplianceSummary.checklistVersionId` 在 dto/ReportService/Generation 三处一致。
