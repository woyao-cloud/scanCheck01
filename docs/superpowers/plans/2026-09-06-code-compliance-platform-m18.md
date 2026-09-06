# M18 通知服务完整化（模板化 + 投递重试 + 硬化收尾）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 版本化通知模板（draft/publish/disable/versions + 内置回落渲染）+ EMAIL/WEBHOOK 指数退避重试（@Scheduled）+ M1/M4/M5/M6 硬化收尾。

**Architecture:** 模块化单体；改动集中在 module-notification（模板子系统 + 投递硬化 + 重试调度），module-result 一行硬化（M4），app-server V15 迁移 + 2 集成测试。发布方（module-scan/report/remediation/result）零改动；SecurityConfig 零改动；通知模块零新外部依赖（@EnableScheduling/@Scheduled 为 Spring Boot 内建，AuditService 在 module-common 已有依赖）。

**Tech Stack:** Spring Boot 3.x（@Scheduled/@EnableScheduling）、Spring Data JPA（版本化模板镜像 report 先例）、Jackson（JSONB content）、MockK/JUnit5（单测）、Testcontainers PG16（集成）。

**Spec:** `docs/superpowers/specs/2026-09-06-code-compliance-platform-m18-design.md`（本计划从 spec 论证，执行者两者同读）

## Global Constraints（spec §10 红线 + 本里程碑锁定裁定，逐条沿用）

1. **发布方零新依赖边**：module-scan/report/remediation/result 不新增对 notification 依赖；M4 只在 module-result 内包 runCatching（不加依赖、不引事件）。
2. **通知模块零新外部依赖**：@Scheduled/@EnableScheduling 为 Spring Boot 内建；AuditService 在 module-common（notification 已有依赖）。
3. **audit_log 只增不改**：publish 写审计 `NOTIFICATION_TEMPLATE_PUBLISHED`，不改 audit_log 表。
4. **DDL 唯一授权**：仅 `V15__notification_template_retry.sql`（2 模板表镜像 V13 + 3 ALTER 对齐 V11 TIMESTAMP + 6 播种 PUBLISHED）。
5. **best-effort 延续**：渲染失败/缺失 → 内置回落，绝不抛；重试逐行隔离；通知失败绝不影响发布方。
6. **无 SecurityConfig 改动**：模板 API 方法级 `@PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_MANAGER')")`。
7. **notify 签名变化只影响唯一调用方** `NotificationEventListener`（同步改），发布方零改动。
8. **裁定 R-M18-1..11**（spec §3.2）：@Scheduled 非 Quartz；完整版本化（本地 `TemplateStatus` 枚举，不依赖 checklist）；`notify(notificationType, variables: Map<String,Any?>, recipients: List<Long>)` 渲染在 service；WEBHOOK 行持久化 `recipient_ids`/`occurred_at`，`WebhookSender.send(row)` 从行读；重试候选 `FAILED AND retryCount<max AND next_retry_at<=now`，退避 `2^(retryCount-1)` 分钟，maxAttempts 默认 5（属性 `compliance.notification.retry.max-attempts`），单策略点 `NotificationRetryBackoff.onFailure`；渲染失败/缺失 → 内置回落绝不抛；缺失变量 → 空串；未知类型 → title=body=type；publish 审计复用 module-common AuditService；模板 API 方法级 @PreAuthorize；重试 job 无外层事务、逐行 runCatching。

## 任务依赖与冲突扫描

| 任务对 | 共享文件 | 产出 vs 消费 | 结论 |
|---|---|---|---|
| T1→T2 | 模板实体/仓库 | T1 产 NotificationTemplate(+Version)/TemplateStatus/两仓库/V15；T2 消费仓库与实体 | 无冲突（顺序依赖，T1 门禁先行） |
| T1→T3 | Notification.kt（+3 列） | T1 产 recipientIds/occurredAt/nextRetryAt；T3 WebhookSender.send(row) 从行读 | 无冲突（T3 在 T1 后） |
| T1→T4 | NotificationRepository | T1 产重试候选查询；T4 NotificationRetryJob 消费 | 无冲突 |
| T2→T4 | TemplateRenderer | T2 产 renderer；T4 NotificationService 注入 | 无冲突 |
| T3→T4 | EmailSender/WebhookSender/RetryBackoff | T3 产 senders(row)+backoff；T4 NotificationService 调用 send(row)、RetryJob 分发 | 无冲突 |
| T4→T5 | 全部 | T5 集成测试消费模板 API/重试 job/事件 | 无冲突 |
| T1→T5 | V15 | T5 集成测试上下文启动 Flyway 跑 V15 | 无冲突 |

每任务自洽核对要点：
- T1：实体字段 ↔ V15 DDL ↔ 仓库方法名 ↔ 实体包路径；门禁命令有效（SmokeIntegrationTest 存在）。
- T2：模板服务 ↔ 镜像 ReportTemplateService 同构（content JSONB 替代 sections）；渲染器解析一次 PUBLISHED（title/body 同一版）。
- T3：backoff 退避序列 ↔ 测试断言；send(row) 契约 ↔ sender 自身测试；WebhookSender 注入 ObjectMapper（Spring Boot Jackson 自动配置 bean）。
- T4：notify 新签名 = 监听器 6 handler 调用 = 监听器测试 verify（三处一致）；重试候选查询名 = job 调用 = job 测试 verify。
- T5：M18TMP*/M18NTF*/M18RTY* 前缀全局唯一；模板集成测试方法内发布→断言→disable→断言（结束态禁用 → 内置回落 == 播种默认文本，不污染共享容器内其他类断言）。

## 计划级裁定（writing-plans 阶段已定，执行期沿用）

- **Ruling PL-M18-1**: 模板控制器 RBAC 正负例放集成测试（完整 SecurityConfig），切片只测业务路径 —— module-notification 测试类路径无 SecurityConfig（app-server 才有），@WebMvcTest(addFilters=false) 下方法级 @PreAuthorize 不生效；镜像 M12 report 先例（ReportTemplateControllerTest 注释同款声明）。
- **Ruling PL-M18-2**: WebhookSender 的 ObjectMapper 改构造注入（非字段）—— M1「序列化异常 → onFailure」需可测；Spring Boot 提供 ObjectMapper bean，生产注入不变，单测可注入 mock 强制 writeValueAsString 抛异常。
- **Ruling PL-M18-3**: M18NotificationTemplateIntegrationTest 把「发布自定义模板 → 断言 → disable → 断言回落」放**单个测试方法**内 —— 非默认 PUBLISHED 模板只在方法内存在；结束态 SCAN_COMPLETED 为禁用（内置回落 == 播种默认文本，M17Event 等对默认标题的断言在任何顺序下都成立）。
- **Ruling PL-M18-4**: M18NotificationRetryIntegrationTest 用 `@TestPropertySource` 开 webhook-url + 放大 fixed-delay-ms 至 1h —— 隔离到独立上下文；@Scheduled 初始 run 在上下文启动时（无 FAILED 行 → no-op），1h 内不会自动触发干扰；重试触发通过注入 job 直接调 `retryFailed()`，退避门用「next_retry_at 拨回过去」模拟流逝（spec §9.3 要求手动触发避免等 fixedDelay）。

---
---

### Task 1: 模板实体 + V15 DDL + 通知表补列 + 仓库

**Files:**
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/domain/TemplateStatus.kt`
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/domain/NotificationTemplate.kt`
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/domain/NotificationTemplateVersion.kt`
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/infrastructure/NotificationTemplateRepository.kt`
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/infrastructure/NotificationTemplateVersionRepository.kt`
- Create: `app-server/src/main/resources/db/migration/V15__notification_template_retry.sql`
- Modify: `module-notification/src/main/kotlin/com/example/compliance/notification/domain/Notification.kt`（+3 列）
- Modify: `module-notification/src/main/kotlin/com/example/compliance/notification/infrastructure/NotificationRepository.kt`（+重试候选查询）

**Interfaces:**
- Consumes: `BaseEntity`（module-common，已有依赖）、`TemplateStatus`（本任务产）。
- Produces: `NotificationTemplate`（`templateType`/`name`/`description`/`version`）、`NotificationTemplateVersion`（`templateId`/`versionNo`/`status: TemplateStatus`/`content: String JSONB`/`createdBy`/`version`）、`NotificationTemplateRepository.findByTemplateType(String): NotificationTemplate?`、`NotificationTemplateVersionRepository.findByTemplateIdOrderByVersionNoDesc(Long): List<...>` + `findFirstByTemplateIdAndStatusOrderByIdDesc(Long, TemplateStatus): ...?`、`Notification` 增 `recipientIds: String?`/`occurredAt: Instant?`/`nextRetryAt: Instant?`、`NotificationRepository.findByStatusAndChannelInAndRetryCountLessThanAndNextRetryAtLessThanEqual(String, Collection<String>, Int, Instant): List<Notification>`。

- [x] **Step 1: 写 TemplateStatus 枚举**

创建 `module-notification/src/main/kotlin/com/example/compliance/notification/domain/TemplateStatus.kt`（R-M18-3：本地枚举，不复用 checklist VersionStatus —— notification 无 checklist 依赖）：

```kotlin
package com.example.compliance.notification.domain

/** 通知模板版本状态（本地枚举，镜像 checklist VersionStatus 值语义；notification 不依赖 checklist —— R-M18-3）。 */
enum class TemplateStatus { DRAFT, PUBLISHED, DISABLED }
```

- [x] **Step 2: 写 NotificationTemplate 实体**

创建 `module-notification/src/main/kotlin/com/example/compliance/notification/domain/NotificationTemplate.kt`（逐字镜像 `ReportTemplate`，content 放版本表）：

```kotlin
package com.example.compliance.notification.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Version

/** 通知模板主线：每类型一条（6 固定类型），版本历史在 NotificationTemplateVersion。镜像 ReportTemplate。 */
@Entity
@Table(name = "notification_template")
class NotificationTemplate : BaseEntity() {
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

- [x] **Step 3: 写 NotificationTemplateVersion 实体**

创建 `module-notification/src/main/kotlin/com/example/compliance/notification/domain/NotificationTemplateVersion.kt`（镜像 `ReportTemplateVersion`，sections→content 为 JSONB `{"title","body"}`）：

```kotlin
package com.example.compliance.notification.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/** 通知模板版本：DRAFT→PUBLISHED→DISABLED。content 为 JSONB `{"title": "...", "body": "..."}`（镜像 report 先例）。 */
@Entity
@Table(name = "notification_template_version")
class NotificationTemplateVersion : BaseEntity() {
    @Column(name = "template_id", nullable = false)
    var templateId: Long = 0
    @Column(name = "version_no", nullable = false)
    var versionNo: Int = 0
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: TemplateStatus = TemplateStatus.DRAFT
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", columnDefinition = "jsonb")
    lateinit var content: String
    @Column(name = "created_by")
    var createdBy: Long? = null
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
```

- [x] **Step 4: 写两个模板仓库**

创建 `module-notification/src/main/kotlin/com/example/compliance/notification/infrastructure/NotificationTemplateRepository.kt`：

```kotlin
package com.example.compliance.notification.infrastructure

import com.example.compliance.notification.domain.NotificationTemplate
import org.springframework.data.jpa.repository.JpaRepository

interface NotificationTemplateRepository : JpaRepository<NotificationTemplate, Long> {
    fun findByTemplateType(templateType: String): NotificationTemplate?
}
```

创建 `module-notification/src/main/kotlin/com/example/compliance/notification/infrastructure/NotificationTemplateVersionRepository.kt`：

```kotlin
package com.example.compliance.notification.infrastructure

import com.example.compliance.notification.domain.NotificationTemplateVersion
import com.example.compliance.notification.domain.TemplateStatus
import org.springframework.data.jpa.repository.JpaRepository

interface NotificationTemplateVersionRepository : JpaRepository<NotificationTemplateVersion, Long> {
    fun findByTemplateIdOrderByVersionNoDesc(templateId: Long): List<NotificationTemplateVersion>
    fun findFirstByTemplateIdAndStatusOrderByIdDesc(templateId: Long, status: TemplateStatus): NotificationTemplateVersion?
}
```

- [x] **Step 5: Notification 实体补 3 列（重试/payload 重建）**

在 `module-notification/.../domain/Notification.kt` 的 `errorMessage` 字段后追加（spec §5.2，Instant 已 import）：

```kotlin
    @Column(name = "recipient_ids")
    var recipientIds: String? = null          // WEBHOOK payload 重建：逗号串 userIds（R-M18-5）
    @Column(name = "occurred_at")
    var occurredAt: Instant? = null           // WEBHOOK payload 重建：事件发生时刻（R-M18-5）
    @Column(name = "next_retry_at")
    var nextRetryAt: Instant? = null          // 指数退避下次重试门（R-M18-6）
```

- [x] **Step 6: NotificationRepository 加重试候选查询**

在 `module-notification/.../infrastructure/NotificationRepository.kt` 末尾追加（spec §5.5 候选 `FAILED AND retryCount<max AND next_retry_at<=now`）：

```kotlin

    /** 重试候选（M18 §5.5，R-M18-6）：FAILED 且未达最大次数且退避到期。 */
    fun findByStatusAndChannelInAndRetryCountLessThanAndNextRetryAtLessThanEqual(
        status: String,
        channels: Collection<String>,
        maxAttempts: Int,
        now: Instant,
    ): List<Notification>
```

- [x] **Step 7: 写 V15 迁移（唯一授权 DDL）**

创建 `app-server/src/main/resources/db/migration/V15__notification_template_retry.sql`（spec §7 逐字；镜像 V13 模板表 + V11/V14 TIMESTAMP 风格 + 6 播种 PUBLISHED）：

```sql
-- M18: 通知模板（版本化，镜像 report_template 先例）+ 通知表补重试/Webhook payload 列
CREATE TABLE notification_template (
    id            BIGSERIAL PRIMARY KEY,
    template_type VARCHAR(32)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   TEXT,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_notification_template_type ON notification_template(template_type);

CREATE TABLE notification_template_version (
    id          BIGSERIAL PRIMARY KEY,
    template_id BIGINT       NOT NULL REFERENCES notification_template(id),
    version_no  INT          NOT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    content     JSONB        NOT NULL,
    created_by  BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (template_id, version_no)
);
CREATE INDEX idx_ntv_template ON notification_template_version (template_id, version_no);

ALTER TABLE notification ADD COLUMN recipient_ids TEXT;
ALTER TABLE notification ADD COLUMN occurred_at  TIMESTAMP;
ALTER TABLE notification ADD COLUMN next_retry_at TIMESTAMP;

-- 播种 6 类型默认 PUBLISHED 模板（version_no=1，内容=M17 现有硬编码标题/正文）
INSERT INTO notification_template (template_type, name) VALUES
    ('SCAN_COMPLETED',              'scan completed'),
    ('REPORT_SNAPSHOT_GENERATED',   'report snapshot generated'),
    ('REMEDIATION_ASSIGNED',        'remediation assigned'),
    ('REMEDIATION_COMPLETED',       'remediation completed'),
    ('FINDING_REGRESSION',          'finding regressed'),
    ('REMEDIATION_WAIVER',          'finding waived');

INSERT INTO notification_template_version (template_id, version_no, status, content) VALUES
    ((SELECT id FROM notification_template WHERE template_type = 'SCAN_COMPLETED'), 1, 'PUBLISHED',
     '{"title":"scan completed","body":"扫描 {scanTaskId} 完成：{status}"}'),
    ((SELECT id FROM notification_template WHERE template_type = 'REPORT_SNAPSHOT_GENERATED'), 1, 'PUBLISHED',
     '{"title":"report snapshot generated","body":"快照 {snapshotId} 已生成（{snapshotType}）"}'),
    ((SELECT id FROM notification_template WHERE template_type = 'REMEDIATION_ASSIGNED'), 1, 'PUBLISHED',
     '{"title":"remediation assigned","body":"finding {findingId} 已指派给你"}'),
    ((SELECT id FROM notification_template WHERE template_type = 'REMEDIATION_COMPLETED'), 1, 'PUBLISHED',
     '{"title":"remediation completed","body":"finding {findingId} 已标记完成"}'),
    ((SELECT id FROM notification_template WHERE template_type = 'FINDING_REGRESSION'), 1, 'PUBLISHED',
     '{"title":"finding regressed","body":"回归：{findingCount} 个 finding 在扫描 {scanTaskId} 复现"}'),
    ((SELECT id FROM notification_template WHERE template_type = 'REMEDIATION_WAIVER'), 1, 'PUBLISHED',
     '{"title":"finding waived","body":"finding {findingId} 被豁免（{reason}）"}');
```

> 表中 status 用 VARCHAR + `'PUBLISHED'`（枚举 STRING 映射），与 V13 一致。

- [x] **Step 8: 门禁 —— module-notification 编译 + V15 迁移验证**

Run: `./gradlew :module-notification:build` 然后 `./gradlew :app-server:test --tests "com.example.compliance.SmokeIntegrationTest"`
Expected: module-notification BUILD SUCCESSFUL；SmokeIntegrationTest 通过（上下文启动跑 Flyway V1..V15，V15 应用 + 校验通过）。

- [x] **Step 9: 提交**

```bash
git add module-notification/src/main/kotlin/com/example/compliance/notification/domain/TemplateStatus.kt \
        module-notification/src/main/kotlin/com/example/compliance/notification/domain/NotificationTemplate.kt \
        module-notification/src/main/kotlin/com/example/compliance/notification/domain/NotificationTemplateVersion.kt \
        module-notification/src/main/kotlin/com/example/compliance/notification/infrastructure/NotificationTemplateRepository.kt \
        module-notification/src/main/kotlin/com/example/compliance/notification/infrastructure/NotificationTemplateVersionRepository.kt \
        module-notification/src/main/kotlin/com/example/compliance/notification/domain/Notification.kt \
        module-notification/src/main/kotlin/com/example/compliance/notification/infrastructure/NotificationRepository.kt \
        app-server/src/main/resources/db/migration/V15__notification_template_retry.sql
git commit -m "feat(notification): M18 T1 — versioned template entities + V15 migration + retry columns"
```

---
---

### Task 2: 模板子系统 —— 渲染器 + 生命周期服务 + API

**Files:**
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/application/DefaultNotificationTemplates.kt`
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/application/TemplateRenderer.kt`
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationTemplateService.kt`
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/api/NotificationTemplateDtos.kt`
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/api/NotificationTemplateController.kt`
- Test: `module-notification/src/test/kotlin/com/example/compliance/notification/application/TemplateRendererTest.kt`
- Test: `module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationTemplateServiceTest.kt`
- Test: `module-notification/src/test/kotlin/com/example/compliance/notification/api/NotificationTemplateControllerTest.kt`

**Interfaces:**
- Consumes: `NotificationTemplateRepository`/`NotificationTemplateVersionRepository`/`TemplateStatus`（T1）；`AuditService.record(action, module, userId?, resourceType?, resourceId?, detail?, ip?)`（module-common）；`BusinessException(code, message)`；`ApiResponse.ok(data)`。
- Produces: `DefaultNotificationTemplates.TITLES: Map<String,String>` + `BODIES: Map<String,String>`（6 类型，与 V15 播种同文）；`TemplateRenderer.render(notificationType: String, variables: Map<String, Any?>): Pair<String, String>`（绝不抛）；`NotificationTemplateService.draft(type, name?, content: JsonNode): NotificationTemplateVersion` / `publish(type)` / `disable(type)` / `versions(type): List<...>`；`NotificationTemplateController`（`/api/v1/notification-templates/{type}/draft|publish|disable|versions`，方法级 @PreAuthorize）；`DraftRequest`/`TemplateVersionView`。

- [x] **Step 1: 写内置默认模板常量**

创建 `module-notification/.../application/DefaultNotificationTemplates.kt`（spec §4.3 逐字；与 V15 播种同文，渲染回落用）：

```kotlin
package com.example.compliance.notification.application

/** 6 类型内置默认标题/正文（= V15 播种同文，Kotlin 常量）。渲染无 PUBLISHED 模板时回落（R-M18-7）。 */
object DefaultNotificationTemplates {
    val TITLES: Map<String, String> = mapOf(
        "SCAN_COMPLETED" to "scan completed",
        "REPORT_SNAPSHOT_GENERATED" to "report snapshot generated",
        "REMEDIATION_ASSIGNED" to "remediation assigned",
        "REMEDIATION_COMPLETED" to "remediation completed",
        "FINDING_REGRESSION" to "finding regressed",
        "REMEDIATION_WAIVER" to "finding waived",
    )
    val BODIES: Map<String, String> = mapOf(
        "SCAN_COMPLETED" to "扫描 {scanTaskId} 完成：{status}",
        "REPORT_SNAPSHOT_GENERATED" to "快照 {snapshotId} 已生成（{snapshotType}）",
        "REMEDIATION_ASSIGNED" to "finding {findingId} 已指派给你",
        "REMEDIATION_COMPLETED" to "finding {findingId} 已标记完成",
        "FINDING_REGRESSION" to "回归：{findingCount} 个 finding 在扫描 {scanTaskId} 复现",
        "REMEDIATION_WAIVER" to "finding {findingId} 被豁免（{reason}）",
    )
}
```

- [x] **Step 2: 写渲染器**

创建 `module-notification/.../application/TemplateRenderer.kt`（spec §4.4 逐字；一次解析 PUBLISHED 版本，缺失/解析失败回落内置，绝不抛）：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.TemplateStatus
import com.example.compliance.notification.infrastructure.NotificationTemplateRepository
import com.example.compliance.notification.infrastructure.NotificationTemplateVersionRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class TemplateRenderer(
    private val templateRepository: NotificationTemplateRepository,
    private val versionRepository: NotificationTemplateVersionRepository,
) {
    private val objectMapper = ObjectMapper()

    /** 解析 type 的 PUBLISHED 模板渲染 title/body；无 PUBLISHED/解析失败 → 内置回落。绝不抛出（R-M18-7）。 */
    fun render(notificationType: String, variables: Map<String, Any?>): Pair<String, String> {
        // 一次性解析 PUBLISHED 版本（title/body 同一版），避免双次仓库查询
        val published = templateRepository.findByTemplateType(notificationType)
            ?.let { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(it.id!!, TemplateStatus.PUBLISHED) }
        val resolved: Pair<String, String>? = published?.let { v ->
            runCatching {
                val node = objectMapper.readTree(v.content)
                node["title"].asText() to node["body"].asText()
            }.getOrNull()
        }
        val (title, body) = resolved
            ?: (DefaultNotificationTemplates.TITLES[notificationType] ?: notificationType) to
               (DefaultNotificationTemplates.BODIES[notificationType] ?: notificationType)
        return replace(title, variables) to replace(body, variables)
    }

    private fun replace(text: String, variables: Map<String, Any?>): String =
        variables.entries.fold(text) { acc, (k, v) -> acc.replace("{$k}", v?.toString() ?: "") }
}
```

- [x] **Step 3: 写模板生命周期服务**

创建 `module-notification/.../application/NotificationTemplateService.kt`（spec §4.5 逐字；镜像 ReportTemplateService，content JSONB 替代 sections，审计 action `NOTIFICATION_TEMPLATE_PUBLISHED`）：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.common.audit.AuditService
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.notification.domain.NotificationTemplate
import com.example.compliance.notification.domain.NotificationTemplateVersion
import com.example.compliance.notification.domain.TemplateStatus
import com.example.compliance.notification.infrastructure.NotificationTemplateRepository
import com.example.compliance.notification.infrastructure.NotificationTemplateVersionRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 通知模板版本管理（镜像 ReportTemplateService）：DRAFT 编辑 → PUBLISH 生效 → DISABLE 停用。 */
@Service
class NotificationTemplateService(
    private val templateRepository: NotificationTemplateRepository,
    private val versionRepository: NotificationTemplateVersionRepository,
    private val auditService: AuditService,
) {
    private val objectMapper = ObjectMapper()

    companion object {
        /** 固定 6 通知类型（与 M17 一致；新增类型走迁移播种 + 内置回落机制）。 */
        val NOTIFICATION_TYPES = setOf(
            "SCAN_COMPLETED", "REPORT_SNAPSHOT_GENERATED", "REMEDIATION_ASSIGNED",
            "REMEDIATION_COMPLETED", "FINDING_REGRESSION", "REMEDIATION_WAIVER",
        )
    }

    private fun requireType(type: String) {
        if (type !in NOTIFICATION_TYPES) throw BusinessException(400, "unsupported notification type: $type")
    }

    @Transactional
    fun draft(type: String, name: String?, content: JsonNode): NotificationTemplateVersion {
        requireType(type)
        val template = templateRepository.findByTemplateType(type)
            ?: templateRepository.save(NotificationTemplate().apply {
                templateType = type
                this.name = name ?: type.lowercase()
            })
        if (name != null) template.name = name
        return currentDraftOrNew(template.id!!, content)
    }

    @Transactional
    fun publish(type: String): NotificationTemplateVersion {
        requireType(type)
        val template = templateRepository.findByTemplateType(type)
            ?: throw BusinessException(404, "no notification template for type: $type")
        val version = versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(template.id!!, TemplateStatus.DRAFT)
            ?: throw BusinessException(400, "no draft notification template version to publish for: $type")
        // 镜像 R-M12-7：发布前把既有 PUBLISHED 全部降级 DISABLED（线性状态机单一活跃版）
        versionRepository.findByTemplateIdOrderByVersionNoDesc(template.id!!)
            .filter { it.status == TemplateStatus.PUBLISHED }
            .forEach { it.status = TemplateStatus.DISABLED; versionRepository.save(it) }
        version.status = TemplateStatus.PUBLISHED
        val saved = versionRepository.save(version)
        auditService.record(
            "NOTIFICATION_TEMPLATE_PUBLISHED", "notification_template", 1L, "notification_template_version",
            saved.id, objectMapper.writeValueAsString(mapOf("type" to type, "versionNo" to saved.versionNo)),
        )
        return saved
    }

    @Transactional
    fun disable(type: String): NotificationTemplateVersion {
        requireType(type)
        val template = templateRepository.findByTemplateType(type)
            ?: throw BusinessException(404, "no notification template for type: $type")
        val versions = versionRepository.findByTemplateIdOrderByVersionNoDesc(template.id!!)
        val active = versions.firstOrNull { it.status == TemplateStatus.PUBLISHED }
            ?: throw BusinessException(400, "no published notification template version to disable for: $type")
        active.status = TemplateStatus.DISABLED
        return versionRepository.save(active)
    }

    @Transactional(readOnly = true)
    fun versions(type: String): List<NotificationTemplateVersion> {
        requireType(type)
        val template = templateRepository.findByTemplateType(type)
            ?: throw BusinessException(404, "no notification template for type: $type")
        return versionRepository.findByTemplateIdOrderByVersionNoDesc(template.id!!)
    }

    private fun currentDraftOrNew(templateId: Long, content: JsonNode): NotificationTemplateVersion {
        versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(templateId, TemplateStatus.DRAFT)
            ?.let { draft ->
                draft.content = objectMapper.writeValueAsString(content)
                return versionRepository.save(draft)
            }
        val latest = versionRepository.findByTemplateIdOrderByVersionNoDesc(templateId).firstOrNull()
        val nextNo = (latest?.versionNo ?: 0) + 1
        return versionRepository.save(NotificationTemplateVersion().apply {
            this.templateId = templateId
            versionNo = nextNo
            status = TemplateStatus.DRAFT
            this.content = objectMapper.writeValueAsString(content)
        })
    }
}
```

- [x] **Step 4: 写 DTO**

创建 `module-notification/.../api/NotificationTemplateDtos.kt`（spec §4.7 逐字；镜像 ReportTemplateDtos，sections→content）：

```kotlin
package com.example.compliance.notification.api

import com.example.compliance.notification.domain.NotificationTemplateVersion
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.constraints.NotNull

data class DraftRequest(
    val name: String? = null,
    @field:NotNull
    val content: JsonNode? = null,
)

data class TemplateVersionView(
    val templateId: Long,
    val versionNo: Int,
    val status: String,
    val content: JsonNode,
) {
    companion object {
        private val mapper = ObjectMapper()
        fun from(v: NotificationTemplateVersion) = TemplateVersionView(v.templateId, v.versionNo, v.status.name, mapper.readTree(v.content))
    }
}
```

- [x] **Step 5: 写控制器**

创建 `module-notification/.../api/NotificationTemplateController.kt`（spec §4.7 逐字；方法级 @PreAuthorize，无 SecurityConfig 改动）：

```kotlin
package com.example.compliance.notification.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.notification.application.NotificationTemplateService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 通知模板管理（仅 ADMIN/COMPLIANCE_MANAGER，方法级 @PreAuthorize —— R-M18-10，无 SecurityConfig 改动）。 */
@RestController
@RequestMapping("/api/v1/notification-templates")
class NotificationTemplateController(private val service: NotificationTemplateService) {

    @PostMapping("/{type}/draft")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_MANAGER')")
    fun draft(@PathVariable type: String, @Valid @RequestBody req: DraftRequest): ApiResponse<TemplateVersionView> =
        ApiResponse.ok(TemplateVersionView.from(service.draft(type, req.name, req.content!!)))

    @PostMapping("/{type}/publish")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_MANAGER')")
    fun publish(@PathVariable type: String): ApiResponse<TemplateVersionView> =
        ApiResponse.ok(TemplateVersionView.from(service.publish(type)))

    @PostMapping("/{type}/disable")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_MANAGER')")
    fun disable(@PathVariable type: String): ApiResponse<TemplateVersionView> =
        ApiResponse.ok(TemplateVersionView.from(service.disable(type)))

    @GetMapping("/{type}/versions")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_MANAGER')")
    fun versions(@PathVariable type: String): ApiResponse<List<TemplateVersionView>> =
        ApiResponse.ok(service.versions(type).map { TemplateVersionView.from(it) })
}
```

- [x] **Step 6: 写渲染器单测**

创建 `module-notification/src/test/kotlin/com/example/compliance/notification/application/TemplateRendererTest.kt`（spec §9.1 覆盖：占位符替换含中文、缺失变量空串、无 PUBLISHED 回落、未知类型回落、有 PUBLISHED 用模板内容、content 解析失败回落）：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.NotificationTemplate
import com.example.compliance.notification.domain.NotificationTemplateVersion
import com.example.compliance.notification.domain.TemplateStatus
import com.example.compliance.notification.infrastructure.NotificationTemplateRepository
import com.example.compliance.notification.infrastructure.NotificationTemplateVersionRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TemplateRendererTest {
    private val templateRepository = mockk<NotificationTemplateRepository>()
    private val versionRepository = mockk<NotificationTemplateVersionRepository>()
    private val renderer = TemplateRenderer(templateRepository, versionRepository)

    private fun published(id: Long = 2L, content: String): NotificationTemplateVersion =
        NotificationTemplateVersion().apply {
            templateId = 1L; versionNo = id.toInt(); status = TemplateStatus.PUBLISHED; this.content = content
        }

    @Test
    fun `missing published falls back to built-in defaults`() {
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns null
        val (title, body) = renderer.render("SCAN_COMPLETED", mapOf("scanTaskId" to 1L, "status" to "SUCCESS"))
        assertEquals("scan completed", title)
        assertEquals("扫描 1 完成：SUCCESS", body)   // 中文正文占位符替换
    }

    @Test
    fun `missing variable is replaced with empty string`() {
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns null
        val (_, body) = renderer.render("SCAN_COMPLETED", emptyMap())
        assertEquals("扫描  完成：", body)   // {scanTaskId}/{status} 均空串
    }

    @Test
    fun `unknown type falls back to the type itself`() {
        every { templateRepository.findByTemplateType("UNKNOWN") } returns null
        val (title, body) = renderer.render("UNKNOWN", emptyMap())
        assertEquals("UNKNOWN", title)
        assertEquals("UNKNOWN", body)   // R-M18-8 退化分支
    }

    @Test
    fun `published version content is rendered`() {
        val template = NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED" }
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, TemplateStatus.PUBLISHED) } returns
            published(content = """{"title":"custom t","body":"任务 {scanTaskId} 完成"}""")
        val (title, body) = renderer.render("SCAN_COMPLETED", mapOf("scanTaskId" to 5L))
        assertEquals("custom t", title)
        assertEquals("任务 5 完成", body)
    }

    @Test
    fun `content json parse failure falls back to built-in`() {
        val template = NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED" }
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, TemplateStatus.PUBLISHED) } returns
            published(content = "not json")
        val (title, body) = renderer.render("SCAN_COMPLETED", mapOf("scanTaskId" to 1L, "status" to "SUCCESS"))
        assertEquals("scan completed", title)
        assertEquals("扫描 1 完成：SUCCESS", body)   // R-M18-7：解析失败绝不抛，回落内置
    }
}
```

- [x] **Step 7: 写模板服务单测**

创建 `module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationTemplateServiceTest.kt`（镜像 ReportTemplateServiceTest 全部用例，TemplateStatus/content 替换）：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.common.audit.AuditService
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.notification.domain.NotificationTemplate
import com.example.compliance.notification.domain.NotificationTemplateVersion
import com.example.compliance.notification.domain.TemplateStatus
import com.example.compliance.notification.infrastructure.NotificationTemplateRepository
import com.example.compliance.notification.infrastructure.NotificationTemplateVersionRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NotificationTemplateServiceTest {
    private val templateRepository = mockk<NotificationTemplateRepository>(relaxed = true)
    private val versionRepository = mockk<NotificationTemplateVersionRepository>(relaxed = true)
    private val auditService = mockk<AuditService>(relaxed = true)
    private val service = NotificationTemplateService(templateRepository, versionRepository, auditService)
    private val mapper = ObjectMapper()

    private fun content(s: String): JsonNode = mapper.readTree(s)
    private fun version(id: Long, templateId: Long = 1L, versionNo: Int, status: TemplateStatus, body: String = "{}") =
        NotificationTemplateVersion().apply { this.id = id; this.templateId = templateId; this.versionNo = versionNo; this.status = status; this.content = body }

    @Test
    fun `first draft creates template line and version V1`() {
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns null
        // save 桩必须给模板赋 id —— service 用 template.id!! 进 currentDraftOrNew，不设会 NPE
        every { templateRepository.save(any()) } answers { firstArg<NotificationTemplate>().also { it.id = 42L } }
        // MockK relaxed 对 nullable 返回类型会返回 child mock 而非 null —— 必须显式桩 null
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(42L, TemplateStatus.DRAFT) } returns null
        every { versionRepository.save(any()) } answers { firstArg() }
        val v = service.draft("SCAN_COMPLETED", "scan", content("""{"title":"t","body":"b"}"""))

        verify { templateRepository.save(any()) }
        assertEquals(1, v.versionNo)
        assertEquals(TemplateStatus.DRAFT, v.status)
        assertTrue(v.content.contains("\"title\":\"t\""))
    }

    @Test
    fun `redraft updates existing draft version instead of opening new one`() {
        val template = NotificationTemplate().apply { id = 1L; templateType = "COMPLIANCE"; name = "c" }
        val draft = version(5L, versionNo = 1, status = TemplateStatus.DRAFT)
        every { templateRepository.findByTemplateType("COMPLIANCE") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, TemplateStatus.DRAFT) } returns draft
        every { versionRepository.save(any()) } answers { firstArg() }

        val updated = service.draft("COMPLIANCE", null, content("""{"title":"t","body":"New"}"""))
        assertEquals(1, updated.versionNo)
        assertTrue(updated.content.contains("New"))
    }

    @Test
    fun `draft after publish opens next version`() {
        val template = NotificationTemplate().apply { id = 1L; templateType = "TREND"; name = "t" }
        val published = version(9L, versionNo = 1, status = TemplateStatus.PUBLISHED)
        every { templateRepository.findByTemplateType("TREND") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, TemplateStatus.DRAFT) } returns null
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(published)
        every { versionRepository.save(any()) } answers { firstArg() }

        val v = service.draft("TREND", null, content("{}"))
        assertEquals(2, v.versionNo)
        assertEquals(TemplateStatus.DRAFT, v.status)
    }

    @Test
    fun `publish requires an existing draft and records audit with valid json detail`() {
        val template = NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED"; name = "s" }
        val draft = version(5L, versionNo = 1, status = TemplateStatus.DRAFT)
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, TemplateStatus.DRAFT) } returns draft
        every { versionRepository.save(any()) } answers { firstArg() }

        val published = service.publish("SCAN_COMPLETED")
        assertEquals(TemplateStatus.PUBLISHED, published.status)
        // audit detail 必须是合法 JSON —— match matcher 实际解析校验，而非仅证明调用过
        verify {
            auditService.record(
                "NOTIFICATION_TEMPLATE_PUBLISHED", "notification_template", 1L, "notification_template_version", 5L,
                match { runCatching { mapper.readTree(it) }.isSuccess },
            )
        }
    }

    @Test
    fun `publish without draft throws 400`() {
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns
            NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED"; name = "s" }
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, TemplateStatus.DRAFT) } returns null
        val e = assertFailsWith<BusinessException> { service.publish("SCAN_COMPLETED") }
        assertEquals(400, e.code)
    }

    @Test
    fun `publish demotes prior published version - single active linear machine`() {
        val template = NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED"; name = "s" }
        val v1 = version(5L, versionNo = 1, status = TemplateStatus.PUBLISHED)
        val draft2 = version(6L, versionNo = 2, status = TemplateStatus.DRAFT)
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, TemplateStatus.DRAFT) } returns draft2
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(draft2, v1)
        every { versionRepository.save(any()) } answers { firstArg() }

        val published = service.publish("SCAN_COMPLETED")
        assertEquals(TemplateStatus.PUBLISHED, published.status)
        assertEquals(TemplateStatus.DISABLED, v1.status)   // 旧 PUBLISHED 被降级，v2 成为唯一活跃版
        assertEquals(2, published.versionNo)
    }

    @Test
    fun `disable marks latest published version disabled`() {
        val template = NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED"; name = "s" }
        val published = version(5L, versionNo = 2, status = TemplateStatus.PUBLISHED)
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns template
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(published)
        every { versionRepository.save(any()) } answers { firstArg() }

        val disabled = service.disable("SCAN_COMPLETED")
        assertEquals(TemplateStatus.DISABLED, disabled.status)
    }

    @Test
    fun `disable ignores open draft and targets active published version`() {
        val template = NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED"; name = "s" }
        val draft = version(9L, versionNo = 3, status = TemplateStatus.DRAFT)
        val published = version(8L, versionNo = 2, status = TemplateStatus.PUBLISHED)
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns template
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(draft, published)
        every { versionRepository.save(any()) } answers { firstArg() }

        val disabled = service.disable("SCAN_COMPLETED")
        assertEquals(TemplateStatus.DISABLED, disabled.status)
        assertEquals(2, disabled.versionNo)   // 目标是 PUBLISHED v2，不是 DRAFT v3
    }

    @Test
    fun `disable without published version throws 400`() {
        val template = NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED"; name = "s" }
        val draft = version(9L, versionNo = 1, status = TemplateStatus.DRAFT)
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns template
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(draft)
        val e = assertFailsWith<BusinessException> { service.disable("SCAN_COMPLETED") }
        assertEquals(400, e.code)
    }

    @Test
    fun `versions lists desc`() {
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns
            NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED"; name = "s" }
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns
            listOf(version(9L, versionNo = 2, status = TemplateStatus.DRAFT), version(8L, versionNo = 1, status = TemplateStatus.PUBLISHED))
        val versions = service.versions("SCAN_COMPLETED")
        assertEquals(2, versions[0].versionNo)
        assertEquals(1, versions[1].versionNo)
    }

    @Test
    fun `draft with unknown type rejects`() {
        assertFailsWith<BusinessException> {
            service.draft("NOT_A_TYPE", null, content("{}"))
        }
    }
}
```

- [x] **Step 8: 写控制器切片测试**

创建 `module-notification/src/test/kotlin/com/example/compliance/notification/api/NotificationTemplateControllerTest.kt`（镜像 ReportTemplateControllerTest 业务路径；RBAC 正负例在 T5 集成测试 —— PL-M18-1）：

```kotlin
package com.example.compliance.notification.api

import com.example.compliance.notification.application.NotificationTemplateService
import com.example.compliance.notification.domain.NotificationTemplateVersion
import com.example.compliance.notification.domain.TemplateStatus
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

/** M18 通知模板端点切片（Security 过滤链关闭；RBAC 正负例在集成测试走完整链 —— 镜像 M12 report 先例，PL-M18-1）。 */
@WebMvcTest(NotificationTemplateController::class)
@AutoConfigureMockMvc(addFilters = false)
class NotificationTemplateControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var service: NotificationTemplateService

    @TestConfiguration
    class TplServiceConfig {
        @Bean
        fun notificationTemplateService(): NotificationTemplateService = mockk()
    }

    private fun version() = NotificationTemplateVersion().apply {
        id = 5L; templateId = 1L; versionNo = 1; status = TemplateStatus.DRAFT
        content = """{"title":"scan completed","body":"body"}"""
    }

    @Test
    fun `draft returns version view`() {
        every { service.draft("SCAN_COMPLETED", "scan", any()) } returns version()
        mockMvc.perform(
            post("/api/v1/notification-templates/SCAN_COMPLETED/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"scan","content":{"title":"scan completed","body":"body"}}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.versionNo").value(1))
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
    }

    @Test
    fun `publish returns published version`() {
        val published = version().apply { status = TemplateStatus.PUBLISHED }
        every { service.publish("SCAN_COMPLETED") } returns published
        mockMvc.perform(post("/api/v1/notification-templates/SCAN_COMPLETED/publish"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
    }

    @Test
    fun `versions lists versions`() {
        every { service.versions("SCAN_COMPLETED") } returns listOf(version())
        mockMvc.perform(get("/api/v1/notification-templates/SCAN_COMPLETED/versions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].versionNo").value(1))
    }

    @Test
    fun `disable returns disabled version`() {
        val disabled = version().apply { status = TemplateStatus.DISABLED }
        every { service.disable("SCAN_COMPLETED") } returns disabled
        mockMvc.perform(post("/api/v1/notification-templates/SCAN_COMPLETED/disable"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("DISABLED"))
    }
}
```

- [x] **Step 9: 门禁**

Run: `./gradlew :module-notification:test`
Expected: BUILD SUCCESSFUL —— TemplateRendererTest 5 测试 + NotificationTemplateServiceTest 11 测试 + NotificationTemplateControllerTest 4 测试全绿，既有测试回归 0 失败。

- [x] **Step 10: 提交**

```bash
git add module-notification/src/main/kotlin/com/example/compliance/notification/application/DefaultNotificationTemplates.kt \
        module-notification/src/main/kotlin/com/example/compliance/notification/application/TemplateRenderer.kt \
        module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationTemplateService.kt \
        module-notification/src/main/kotlin/com/example/compliance/notification/api/NotificationTemplateDtos.kt \
        module-notification/src/main/kotlin/com/example/compliance/notification/api/NotificationTemplateController.kt \
        module-notification/src/test/kotlin/com/example/compliance/notification/application/TemplateRendererTest.kt \
        module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationTemplateServiceTest.kt \
        module-notification/src/test/kotlin/com/example/compliance/notification/api/NotificationTemplateControllerTest.kt
git commit -m "feat(notification): M18 T2 — versioned template subsystem (renderer/service/api)"
```

---
---

### Task 3: 投递核心硬化 —— 退避策略 + senders（M1/M6 前置）

**Files:**
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationRetryBackoff.kt`
- Modify: `module-notification/src/main/kotlin/com/example/compliance/notification/application/EmailSender.kt`（全文件重写）
- Modify: `module-notification/src/main/kotlin/com/example/compliance/notification/application/WebhookSender.kt`（全文件重写）
- Test: `module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationRetryBackoffTest.kt`
- Test: `module-notification/src/test/kotlin/com/example/compliance/notification/application/EmailSenderTest.kt`（全文件重写）
- Test: `module-notification/src/test/kotlin/com/example/compliance/notification/application/WebhookSenderTest.kt`（全文件重写）

**Interfaces:**
- Consumes: `Notification`（含 T1 的 `recipientIds`/`occurredAt`/`nextRetryAt`）、`NotificationRepository`、`WebhookClient`、`Channel`。
- Produces: `NotificationRetryBackoff(maxAttempts: Int)` 构造注入 `@Value("\${compliance.notification.retry.max-attempts:5}")`，`onFailure(row: Notification, message: String?)`（置 FAILED + retryCount+1 + errorMessage.take(500) + 退避 nextRetryAt，达上限标 `max attempts reached`）；`EmailSender` 构造 `(ObjectProvider<JavaMailSender>, NotificationRepository, NotificationRetryBackoff)`，`send(row)` 成功清 errorMessage/nextRetryAt；`WebhookSender` 构造 `(webhookUrl, WebhookClient, NotificationRepository, NotificationRetryBackoff, ObjectMapper)`（PL-M18-2），`send(row)` 从行读 payload（R-M18-5）、序列化入 try（M1）。

- [x] **Step 1: 写失败策略（单一策略点）**

创建 `module-notification/.../application/NotificationRetryBackoff.kt`（spec §5.3 逐字）：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Notification
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant

/** 投递失败策略（R-M18-6 单一策略点）：指数退避 2^(retryCount-1) 分钟 + 最大次数上限。 */
@Component
class NotificationRetryBackoff(
    @Value("\${compliance.notification.retry.max-attempts:5}") val maxAttempts: Int,
) {
    /** sender 失败路径唯一落点：置 FAILED + retryCount+1 + errorMessage，按退避设 next_retry_at（达上限则不再重试）。 */
    fun onFailure(row: Notification, message: String?) {
        row.status = "FAILED"
        row.retryCount += 1
        row.errorMessage = message?.take(500)
        if (row.retryCount < maxAttempts) {
            row.nextRetryAt = Instant.now().plusSeconds(60L * exp2(row.retryCount - 1))
        } else {
            row.nextRetryAt = null
            row.errorMessage = (row.errorMessage ?: "") + "; max attempts reached"
        }
    }

    /** 2^n（retryCount=1 → 60s，2 → 120s，3 → 240s …），指数退避。 */
    private fun exp2(n: Int): Long = (1L shl n)
}
```

- [x] **Step 2: 重写 EmailSender（onFailure 替换 fail；成功清重试字段）**

全文件替换 `module-notification/.../application/EmailSender.kt`（spec §5.4）：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service
import java.time.Instant

/** EMAIL 渠道适配器（spec R-M17-D5 seam）：依赖 Spring JavaMailSender 接口。
 *  未配置 spring.mail.*（无 JavaMailSender bean）→ isAvailable()=false，不建 EMAIL 行。
 *  send 绝不抛出 —— 所有异常置 FAILED（+retry_count+error_message+next_retry_at），保护 notify 的 REQUIRES_NEW 事务。
 *  M18 §5.4：失败策略统一走 NotificationRetryBackoff.onFailure（单点策略）；成功清 errorMessage/next_retry_at。 */
@Service
class EmailSender(
    private val mailSenderProvider: ObjectProvider<JavaMailSender>,
    private val repository: NotificationRepository,
    private val retryBackoff: NotificationRetryBackoff,
) {
    fun isAvailable(): Boolean = mailSenderProvider.getIfAvailable() != null

    fun send(row: Notification) {
        val mailSender = mailSenderProvider.getIfAvailable()
        if (mailSender == null) {
            retryBackoff.onFailure(row, "mail sender not configured")
        } else {
            try {
                val mime = mailSender.createMimeMessage()
                mime.setFrom(InternetAddress("no-reply@example.com"))
                mime.setRecipients(Message.RecipientType.TO, row.recipient)
                mime.setSubject(row.title, "UTF-8")
                mime.setText(row.content ?: "", "UTF-8")
                mailSender.send(mime)
                row.status = "SENT"
                row.sentAt = Instant.now()
                row.errorMessage = null
                row.nextRetryAt = null
            } catch (e: Exception) {
                retryBackoff.onFailure(row, e.message)
            }
        }
        repository.save(row)
    }
}
```

- [x] **Step 3: 重写 WebhookSender（send(row) 从行读 + 序列化入 try + onFailure）**

全文件替换 `module-notification/.../application/WebhookSender.kt`（spec §5.4 + R-M18-5 + M1 + PL-M18-2）：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant

/** Webhook 渠道适配器：标准化 JSON 负载 + 状态流转。url 未配置 → isConfigured()=false，不建 WEBHOOK 行。
 *  send 绝不抛出 —— client 异常或 false 一律置 FAILED（经 retryBackoff.onFailure），保护 notify 的 REQUIRES_NEW 事务。
 *  M18 §5.4 (R-M18-5)：send(row) 从行读 recipientIds/occurredAt 重建 payload（落库后重试同路径）；
 *  M1：objectMapper.writeValueAsString 移入 try/catch 内。 */
@Service
class WebhookSender(
    @Value("\${compliance.notification.webhook-url:}") private val webhookUrl: String,
    private val client: WebhookClient,
    private val repository: NotificationRepository,
    private val retryBackoff: NotificationRetryBackoff,
    private val objectMapper: ObjectMapper,
) {
    fun isConfigured(): Boolean = webhookUrl.isNotBlank()

    fun send(row: Notification) {
        val ok: Boolean
        try {
            val payload = objectMapper.writeValueAsString(
                mapOf(
                    "type" to row.type,
                    "title" to row.title,
                    "body" to row.content,
                    "recipientIds" to (row.recipientIds?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()),
                    "occurredAt" to row.occurredAt?.toString(),
                )
            )
            ok = client.post(webhookUrl, payload)
        } catch (e: Exception) {
            retryBackoff.onFailure(row, e.message)
            repository.save(row)
            return
        }
        if (ok) {
            row.status = "SENT"
            row.sentAt = Instant.now()
            row.errorMessage = null
            row.nextRetryAt = null
        } else {
            retryBackoff.onFailure(row, "webhook post failed")
        }
        repository.save(row)
    }
}
```

- [x] **Step 4: 写退避策略单测**

创建 `module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationRetryBackoffTest.kt`（spec §9.1：退避序列 + 达上限 + 截断）：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationRetryBackoffTest {
    private val backoff = NotificationRetryBackoff(maxAttempts = 5)

    private fun row() = Notification().apply {
        id = 1L; channel = Channel.EMAIL.name; recipient = "a@x.com"; type = "SCAN_COMPLETED"; status = "PENDING"
    }

    private fun assertWindow(actual: Instant?, seconds: Long) {
        val expected = Instant.now().plusSeconds(seconds)
        assertTrue(actual!!.isAfter(expected.minusSeconds(2)) && actual.isBefore(expected.plusSeconds(2)),
            "expected ~now+${seconds}s, got $actual")
    }

    @Test
    fun `onFailure sets failed increments retry and schedules exponential backoff`() {
        val r = row()
        backoff.onFailure(r, "smtp down")
        assertEquals("FAILED", r.status)
        assertEquals(1, r.retryCount)
        assertEquals("smtp down", r.errorMessage)
        assertWindow(r.nextRetryAt, 60)   // 2^(1-1) 分钟
    }

    @Test
    fun `second failure doubles the backoff window`() {
        val r = row()
        backoff.onFailure(r, "m1")
        backoff.onFailure(r, "m2")
        assertEquals(2, r.retryCount)
        assertWindow(r.nextRetryAt, 120)   // 2^(2-1) 分钟
    }

    @Test
    fun `max attempts reached stops rescheduling and annotates`() {
        val limited = NotificationRetryBackoff(maxAttempts = 2)
        val r = row()
        limited.onFailure(r, "m1")
        limited.onFailure(r, "m2")
        assertEquals(2, r.retryCount)
        assertNull(r.nextRetryAt)
        assertTrue(r.errorMessage!!.contains("max attempts reached"))
    }

    @Test
    fun `error message is truncated to 500 chars`() {
        val r = row()
        backoff.onFailure(r, "x".repeat(600))
        assertEquals(500, r.errorMessage!!.length)
    }
}
```

- [x] **Step 5: 重写 EmailSenderTest（注入 RetryBackoff；成功清重试字段）**

全文件替换 `module-notification/src/test/kotlin/com/example/compliance/notification/application/EmailSenderTest.kt`：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import java.io.InputStream
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmailSenderTest {
    private val provider = mockk<ObjectProvider<JavaMailSender>>()
    private val repository = mockk<NotificationRepository>()
    private val retryBackoff = NotificationRetryBackoff(maxAttempts = 5)
    private val sender = EmailSender(provider, repository, retryBackoff)

    private fun row() = Notification().apply {
        id = 1L; channel = Channel.EMAIL.name; recipient = "a@x.com"; type = "SCAN_COMPLETED"
        title = "scan completed"; content = "body"; status = "PENDING"
    }

    @Test
    fun `send builds mime and marks sent clearing retry fields`() {
        val stub = StubJavaMailSender()
        every { provider.getIfAvailable() } returns stub
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        row.errorMessage = "prev"; row.nextRetryAt = java.time.Instant.now()   // 失败遗留 → 成功应清除
        sender.send(row)
        assertEquals("SENT", row.status)
        assertNotNull(row.sentAt)
        assertNull(row.errorMessage)
        assertNull(row.nextRetryAt)
        assertEquals(1, stub.sent.size)
        assertEquals("a@x.com", stub.sent.single().getRecipients(jakarta.mail.Message.RecipientType.TO)?.first()?.toString())
        assertEquals("scan completed", stub.sent.single().subject)
        verify { repository.save(row) }
    }

    @Test
    fun `send failure marks failed with retry error and backoff gate`() {
        val stub = StubJavaMailSender()
        stub.failWith = RuntimeException("smtp down")
        every { provider.getIfAvailable() } returns stub
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        sender.send(row)
        assertEquals("FAILED", row.status)
        assertEquals(1, row.retryCount)
        assertEquals("smtp down", row.errorMessage)
        assertNotNull(row.nextRetryAt)   // 退避门已排程（R-M18-6）
    }

    @Test
    fun `no sender available marks failed`() {
        every { provider.getIfAvailable() } returns null
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        sender.send(row)
        assertEquals("FAILED", row.status)
        assertEquals("mail sender not configured", row.errorMessage)
    }

    @Test
    fun `isAvailable reflects sender presence`() {
        every { provider.getIfAvailable() } returns StubJavaMailSender()
        assertTrue(sender.isAvailable())
        every { provider.getIfAvailable() } returns null
        assertTrue(!sender.isAvailable())
    }

    /** JavaMailSender 测试替身：捕获 MimeMessage（spec R-M17-D5，不引入 Greenmail）。 */
    class StubJavaMailSender : JavaMailSender {
        val sent = mutableListOf<MimeMessage>()
        var failWith: RuntimeException? = null
        override fun createMimeMessage(): MimeMessage = MimeMessage(jakarta.mail.Session.getInstance(Properties()))
        override fun createMimeMessage(contentStream: InputStream): MimeMessage = MimeMessage(jakarta.mail.Session.getInstance(Properties()), contentStream)
        override fun send(mimeMessage: MimeMessage) { failWith?.let { throw it }; sent += mimeMessage }
        override fun send(vararg mimeMessages: MimeMessage) { mimeMessages.forEach { send(it) } }
        override fun send(vararg simpleMessages: SimpleMailMessage) { }
    }
}
```

- [x] **Step 6: 重写 WebhookSenderTest（send(row) 从行读；序列化异常 → onFailure）**

全文件替换 `module-notification/src/test/kotlin/com/example/compliance/notification/application/WebhookSenderTest.kt`：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebhookSenderTest {
    private val client = mockk<WebhookClient>()
    private val repository = mockk<NotificationRepository>()
    private val retryBackoff = NotificationRetryBackoff(maxAttempts = 5)
    private val sender = WebhookSender("http://hook.test/x", client, repository, retryBackoff, ObjectMapper())

    private fun row() = Notification().apply {
        id = 1L; channel = Channel.WEBHOOK.name; recipient = "webhook"; type = "SCAN_COMPLETED"
        title = "scan completed"; content = "body"; status = "PENDING"
        recipientIds = "1,2"; occurredAt = Instant.parse("2026-09-06T00:00:00Z")
    }

    @Test
    fun `send posts json built from row and marks sent clearing retry fields`() {
        every { client.post("http://hook.test/x", any()) } returns true
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        row.errorMessage = "prev"; row.nextRetryAt = Instant.now()   // 失败遗留 → 成功应清除
        sender.send(row)
        assertEquals("SENT", row.status)
        assertNull(row.errorMessage)
        assertNull(row.nextRetryAt)
        val posted = slot<String>()
        verify { client.post("http://hook.test/x", capture(posted)) }
        assertTrue(posted.captured.contains("\"type\":\"SCAN_COMPLETED\""))
        assertTrue(posted.captured.contains("\"recipientIds\":[1,2]"))   // 从行读 recipient_ids 重建
        assertTrue(posted.captured.contains("2026-09-06T00:00:00Z"))     // 从行读 occurred_at 重建
    }

    @Test
    fun `send failure marks failed and increments retry`() {
        every { client.post(any(), any()) } returns false
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        sender.send(row)
        assertEquals("FAILED", row.status)
        assertEquals(1, row.retryCount)
        assertEquals("webhook post failed", row.errorMessage)
        assertTrue(row.nextRetryAt != null)
    }

    @Test
    fun `send swallows client exception as failed`() {
        every { client.post(any(), any()) } throws RuntimeException("http down")
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        sender.send(row)
        assertEquals("FAILED", row.status)
        assertEquals("http down", row.errorMessage)
    }

    @Test
    fun `serialization failure marks failed via onFailure`() {
        // M1：序列化在 try 内 —— 注入可抛异常的 ObjectMapper，证明异常不外泄、走 onFailure
        val mapper = mockk<ObjectMapper>()
        every { mapper.writeValueAsString(any()) } throws RuntimeException("serialize boom")
        val throwingSender = WebhookSender("http://hook.test/x", client, repository, retryBackoff, mapper)
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        throwingSender.send(row)
        assertEquals("FAILED", row.status)
        assertEquals("serialize boom", row.errorMessage)
        assertEquals(1, row.retryCount)
    }

    @Test
    fun `isConfigured reflects url`() {
        assertTrue(sender.isConfigured())
        assertFalse(WebhookSender("", client, repository, retryBackoff, ObjectMapper()).isConfigured())
    }
}
```

- [x] **Step 7: 门禁**

Run: `./gradlew :module-notification:test`
Expected: BUILD SUCCESSFUL —— NotificationRetryBackoffTest 4 测试 + EmailSenderTest 4 测试 + WebhookSenderTest 5 测试全绿，既有测试回归 0 失败。

- [x] **Step 8: 提交**

```bash
git add module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationRetryBackoff.kt \
        module-notification/src/main/kotlin/com/example/compliance/notification/application/EmailSender.kt \
        module-notification/src/main/kotlin/com/example/compliance/notification/application/WebhookSender.kt \
        module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationRetryBackoffTest.kt \
        module-notification/src/test/kotlin/com/example/compliance/notification/application/EmailSenderTest.kt \
        module-notification/src/test/kotlin/com/example/compliance/notification/application/WebhookSenderTest.kt
git commit -m "feat(notification): M18 T3 — retry backoff strategy + hardened senders (M1)"
```

---
---

### Task 4: notify 签名变化 + 事件变量化 + 重试调度（M5/M6）

**Files:**
- Modify: `module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationService.kt`（全文件重写：新签名 + 渲染器注入 + M6 isAvailable 提升 + M5 断言已存在守卫）
- Modify: `module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationEventListener.kt`（6 handler 改传语义变量）
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationSchedulingConfig.kt`
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationRetryJob.kt`
- Test: `module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationServiceTest.kt`（全文件重写：新签名 + M5）
- Test: `module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationEventListenerTest.kt`（verify 参数改 map）
- Test: `module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationRetryJobTest.kt`

**Interfaces:**
- Consumes: `TemplateRenderer.render(type, variables): Pair<String,String>`（T2）；`EmailSender.send(row)`/`WebhookSender.send(row)`/`NotificationRetryBackoff.maxAttempts`（T3）；`NotificationRepository.findByStatusAndChannelInAndRetryCountLessThanAndNextRetryAtLessThanEqual(...)`（T1）。
- Produces: `NotificationService.notify(notificationType: String, variables: Map<String, Any?>, recipients: List<Long>)`（新签名，唯一调用方 = NotificationEventListener）；`NotificationRetryJob.retryFailed()`（@Scheduled fixedDelayString `compliance.notification.retry.fixed-delay-ms` 默认 60000，无外层事务、逐行 runCatching）；`NotificationSchedulingConfig`（@EnableScheduling）。

- [x] **Step 1: 重写 NotificationService（新签名 + 渲染 + M6）**

全文件替换 `module-notification/.../application/NotificationService.kt`（spec §4.6 + §5.2 语义；listMy/unreadCount/markRead/markReadAll 不变）：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.example.compliance.user.infrastructure.UserRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** M17 通知服务（spec R-M17-D4）：事件 → 站内信落库 + EMAIL/Webhook 真实渠道投递。
 *  REQUIRES_NEW：通知写入必须在独立事务 —— 发布方事务内同步 @EventListener 调用本方法时，
 *  通知失败仅回滚通知自身，绝不标记发布方事务 rollback-only（spec §6.4 best-effort）。
 *  M18 §4.6 (R-M18-4)：签名改 notify(type, variables, recipients)，渲染在 service（所有渠道/收件人共用一次）；
 *  M6：emailSender.isAvailable() 提升出循环（每事件评估一次）。 */
@Service
class NotificationService(
    private val repository: NotificationRepository,
    private val userRepository: UserRepository,
    private val emailSender: EmailSender,
    private val webhookSender: WebhookSender,
    private val renderer: TemplateRenderer,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun notify(notificationType: String, variables: Map<String, Any?>, recipients: List<Long>) {
        val unique = recipients.distinct()
        if (unique.isEmpty()) return
        val occurredAt = Instant.now()
        val (title, body) = renderer.render(notificationType, variables)
        val emailAvailable = emailSender.isAvailable()
        unique.forEach { userId ->
            // IN_APP：每收件人一行，落库即投递（SENT + sentAt）
            repository.save(Notification().apply {
                channel = Channel.IN_APP.name
                recipient = userId.toString()
                type = notificationType
                this.title = title
                content = body
                status = "SENT"
                sentAt = occurredAt
            })
            // EMAIL：仅对有邮箱用户建行；mail sender 未配置 → 跳过（镜像 webhook 未配置，Ruling PL-M17-3）
            if (emailAvailable) {
                val email = userRepository.findById(userId).orElse(null)?.email
                if (!email.isNullOrBlank()) {
                    val row = repository.save(Notification().apply {
                        channel = Channel.EMAIL.name
                        recipient = email
                        type = notificationType
                        this.title = title
                        content = body
                        status = "PENDING"
                    })
                    emailSender.send(row)   // sender 自身吞掉异常 → SENT/FAILED，不抛出
                }
            }
        }
        // WEBHOOK：每事件一行；url 未配置 → 跳过
        if (webhookSender.isConfigured()) {
            val row = repository.save(Notification().apply {
                channel = Channel.WEBHOOK.name
                recipient = "webhook"
                type = notificationType
                this.title = title
                content = body
                status = "PENDING"
                // R-M18-5：payload 重建字段落行 —— 重试与首投同路径
                recipientIds = unique.joinToString(",")
                this.occurredAt = occurredAt
            })
            webhookSender.send(row)
        }
    }

    @Transactional(readOnly = true)
    fun listMy(userId: Long, page: Int, size: Int, unreadOnly: Boolean): Page<Notification> {
        // 硬化（镜像 report list C2 / 审计查询 D6）：负 page 拒绝 400，size 钳制 [1,100]，固定 id 倒序
        if (page < 0) throw BusinessException(400, "page must be non-negative")
        val pageable = PageRequest.of(page, size.coerceIn(1, 100), Sort.by(Sort.Direction.DESC, "id"))
        val recipient = userId.toString()
        return if (unreadOnly) {
            repository.findByRecipientAndChannelAndReadAtIsNull(recipient, Channel.IN_APP.name, pageable)
        } else {
            repository.findByRecipientAndChannel(recipient, Channel.IN_APP.name, pageable)
        }
    }

    @Transactional(readOnly = true)
    fun unreadCount(userId: Long): Long =
        repository.countByRecipientAndChannelAndReadAtIsNull(userId.toString(), Channel.IN_APP.name)

    @Transactional
    fun markRead(id: Long, userId: Long) {
        val row = repository.findByIdAndRecipientAndChannel(id, userId.toString(), Channel.IN_APP.name)
            ?: throw BusinessException(404, "notification not found: $id")
        if (row.readAt == null) {
            row.readAt = Instant.now()
            repository.save(row)
        }
    }

    @Transactional
    fun markReadAll(userId: Long): Int =
        repository.markReadAll(userId.toString(), Channel.IN_APP.name, Instant.now())
}
```

- [x] **Step 2: 重写 NotificationEventListener（6 handler 传语义变量）**

全文件替换 `module-notification/.../application/NotificationEventListener.kt` 的 6 个 handler 方法体（spec §4.6 逐字；safe()/ownerRecipient() 不变）：

```kotlin
    @EventListener
    fun onRegression(e: FindingRegressionEvent) = safe("regression project=${e.projectId}") {
        ownerRecipient(e.projectId)?.let { owner ->
            notificationService.notify(
                "FINDING_REGRESSION",
                mapOf("findingCount" to e.findingIds.size, "scanTaskId" to e.scanTaskId),
                listOf(owner),
            )
        }
    }

    @EventListener
    fun onWaiver(e: RemediationWaiverEvent) = safe("waiver project=${e.projectId}") {
        notificationService.notify(
            "REMEDIATION_WAIVER",
            mapOf("findingId" to e.findingId, "reason" to e.reason),
            listOf(e.actorId),
        )
    }

    @EventListener
    fun onScanCompleted(e: ScanCompletedEvent) = safe("scan project=${e.projectId}") {
        ownerRecipient(e.projectId)?.let { owner ->
            notificationService.notify("SCAN_COMPLETED", mapOf("scanTaskId" to e.scanTaskId, "status" to e.status), listOf(owner))
        }
    }

    @EventListener
    fun onReportSnapshotGenerated(e: ReportSnapshotGeneratedEvent) = safe("report snapshot project=${e.projectId}") {
        e.projectId?.let { projectId -> ownerRecipient(projectId) }?.let { owner ->
            notificationService.notify(
                "REPORT_SNAPSHOT_GENERATED",
                mapOf("snapshotId" to e.snapshotId, "snapshotType" to e.snapshotType),
                listOf(owner),
            )
        }
    }

    @EventListener
    fun onRemediationAssigned(e: RemediationAssignedEvent) = safe("remediation assigned finding=${e.findingId}") {
        notificationService.notify("REMEDIATION_ASSIGNED", mapOf("findingId" to e.findingId), listOf(e.assigneeId))
    }

    @EventListener
    fun onRemediationCompleted(e: RemediationCompletedEvent) = safe("remediation completed finding=${e.findingId}") {
        notificationService.notify(
            "REMEDIATION_COMPLETED",
            mapOf("findingId" to e.findingId),
            listOfNotNull(e.actorId, e.assigneeId).distinct(),
        )
    }
```

- [x] **Step 3: 写调度启用配置**

创建 `module-notification/.../application/NotificationSchedulingConfig.kt`（spec §5.1）：

```kotlin
package com.example.compliance.notification.application

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/** M18 §5.1：启用 @Scheduled（R-M18-1 单实例重试任务；多实例需迁移持久化调度器 —— spec §2.2 note）。 */
@Configuration
@EnableScheduling
class NotificationSchedulingConfig
```

- [x] **Step 4: 写重试 Job**

创建 `module-notification/.../application/NotificationRetryJob.kt`（spec §5.5 逐字；无外层事务 + 逐行 runCatching）：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.infrastructure.NotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/** M18 §5.5 投递重试 Job：FAILED 且未达最大次数且退避到期的 EMAIL/WEBHOOK 行重投。
 *  无外层事务（R-M18-11）：sender 各自落库（auto-commit），单行失败不影响整批；
 *  逐行 runCatching 为双保险（sender 本就不抛）。@Scheduled 默认单线程 + next_retry_at 门防并发。 */
@Component
class NotificationRetryJob(
    private val repository: NotificationRepository,
    private val emailSender: EmailSender,
    private val webhookSender: WebhookSender,
    private val retryBackoff: NotificationRetryBackoff,
) {
    private val log = LoggerFactory.getLogger(NotificationRetryJob::class.java)

    @Scheduled(fixedDelayString = "\${compliance.notification.retry.fixed-delay-ms:60000}")
    fun retryFailed() {
        val candidates = repository.findByStatusAndChannelInAndRetryCountLessThanAndNextRetryAtLessThanEqual(
            "FAILED", listOf(Channel.EMAIL.name, Channel.WEBHOOK.name), retryBackoff.maxAttempts, Instant.now(),
        )
        candidates.forEach { row ->
            runCatching {                                  // R-M18-11 双保险：单行异常不杀批
                when (row.channel) {
                    Channel.EMAIL.name -> emailSender.send(row)
                    Channel.WEBHOOK.name -> webhookSender.send(row)
                }
            }.onFailure { log.warn("retry notification {} failed unexpectedly", row.id, it) }
        }
    }
}
```

- [x] **Step 5: 重写 NotificationServiceTest（新签名 + renderer mock + M5）**

全文件替换 `module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationServiceTest.kt`：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.example.compliance.user.domain.User
import com.example.compliance.user.infrastructure.UserRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NotificationServiceTest {
    private val repository = mockk<NotificationRepository>()
    private val userRepository = mockk<UserRepository>()
    private val emailSender = mockk<EmailSender>()
    private val webhookSender = mockk<WebhookSender>()
    private val renderer = mockk<TemplateRenderer>()
    private val service = NotificationService(repository, userRepository, emailSender, webhookSender, renderer)

    private fun captured(): MutableList<Notification> {
        val rows = mutableListOf<Notification>()
        every { repository.save(any<Notification>()) } answers { firstArg<Notification>().also { it.id = 1L; rows += it } }
        return rows
    }

    /** 渲染桩：title/body 固定值（渲染正确性由 TemplateRendererTest 专测，此处只验证接线与 fan-out）。 */
    private fun stubRender() {
        every { renderer.render(any(), any()) } returns ("t" to "b")
    }

    @Test
    fun `notify dedups recipients and creates one IN_APP row per recipient`() {
        val rows = captured()
        stubRender()
        every { emailSender.isAvailable() } returns false
        every { webhookSender.isConfigured() } returns false
        service.notify("SCAN_COMPLETED", mapOf("scanTaskId" to 1L), listOf(1L, 1L, 2L))
        val inApp = rows.filter { it.channel == Channel.IN_APP.name }
        assertEquals(2, inApp.size)
        assertTrue(inApp.all { it.status == "SENT" && it.type == "SCAN_COMPLETED" && it.sentAt != null })
        assertEquals(listOf("1", "2"), inApp.map { it.recipient })
        verify(exactly = 1) { renderer.render("SCAN_COMPLETED", mapOf("scanTaskId" to 1L)) }   // 每事件渲染一次
    }

    @Test
    fun `notify with empty recipients persists nothing`() {
        service.notify("SCAN_COMPLETED", emptyMap(), emptyList())
        verify(exactly = 0) { repository.save(any<Notification>()) }
    }

    @Test
    fun `email rows only for users with email when sender available`() {
        val rows = captured()
        stubRender()
        every { userRepository.findById(1L) } returns Optional.of(User().apply { id = 1L; email = "a@x.com" })
        every { userRepository.findById(2L) } returns Optional.of(User().apply { id = 2L; email = null })
        every { emailSender.isAvailable() } returns true
        every { emailSender.send(any()) } just Runs
        every { webhookSender.isConfigured() } returns false
        service.notify("REMEDIATION_ASSIGNED", mapOf("findingId" to 3L), listOf(1L, 2L))
        val emails = rows.filter { it.channel == Channel.EMAIL.name }
        assertEquals(1, emails.size)
        assertEquals("a@x.com", emails.single().recipient)
        assertEquals("PENDING", emails.single().status)
        verify(exactly = 1) { emailSender.send(emails.single()) }
    }

    @Test
    fun `no email rows when sender unavailable`() {
        val rows = captured()
        stubRender()
        every { emailSender.isAvailable() } returns false
        every { webhookSender.isConfigured() } returns false
        service.notify("SCAN_COMPLETED", emptyMap(), listOf(1L))
        assertTrue(rows.none { it.channel == Channel.EMAIL.name })
        verify(exactly = 0) { userRepository.findById(any()) }
    }

    @Test
    fun `webhook row only when configured with deduped recipient ids on the row`() {
        val rows = captured()
        stubRender()
        every { emailSender.isAvailable() } returns false
        every { webhookSender.isConfigured() } returns true
        every { webhookSender.send(any()) } just Runs
        service.notify("REPORT_SNAPSHOT_GENERATED", mapOf("snapshotId" to 5L), listOf(1L, 1L, 2L))
        val webhook = rows.filter { it.channel == Channel.WEBHOOK.name }
        assertEquals(1, webhook.size)
        assertEquals("webhook", webhook.single().recipient)
        assertEquals("1,2", webhook.single().recipientIds)   // R-M18-5：去重收件人落行
        verify { webhookSender.send(webhook.single()) }      // 新签名 send(row)
    }

    @Test
    fun `listMy negative page throws business exception`() {
        // M5（spec §6）：listMy 分页守卫直接单测（切片只证明 400 翻译）
        val e = assertFailsWith<BusinessException> { service.listMy(1L, -1, 20, false) }
        assertEquals(400, e.code)
    }
}
```

- [x] **Step 6: 重写 NotificationEventListenerTest（verify 参数改 map）**

全文件替换 `module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationEventListenerTest.kt`：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.common.event.FindingRegressionEvent
import com.example.compliance.common.event.RemediationAssignedEvent
import com.example.compliance.common.event.RemediationCompletedEvent
import com.example.compliance.common.event.RemediationWaiverEvent
import com.example.compliance.common.event.ReportSnapshotGeneratedEvent
import com.example.compliance.common.event.ScanCompletedEvent
import com.example.compliance.project.domain.Project
import com.example.compliance.project.infrastructure.ProjectRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.Optional

class NotificationEventListenerTest {
    private val service = mockk<NotificationService>()
    private val projectRepository = mockk<ProjectRepository>()
    private val listener = NotificationEventListener(service, projectRepository)

    private fun project(owner: Long?) = Project().apply { this.id = 1L; ownerUserId = owner }

    @Test
    fun `regression resolves project owner as recipient with semantic variables`() {
        every { projectRepository.findById(1L) } returns Optional.of(project(7L))
        listener.onRegression(FindingRegressionEvent(1L, 2L, listOf(3L)))
        verify { service.notify("FINDING_REGRESSION", mapOf("findingCount" to 1, "scanTaskId" to 2L), listOf(7L)) }
    }

    @Test
    fun `regression with no owner notifies nothing`() {
        every { projectRepository.findById(1L) } returns Optional.of(project(null))
        listener.onRegression(FindingRegressionEvent(1L, 2L, listOf(3L)))
        verify(exactly = 0) { service.notify(any(), any(), any()) }
    }

    @Test
    fun `regression with missing project notifies nothing`() {
        every { projectRepository.findById(99L) } returns Optional.empty()
        listener.onRegression(FindingRegressionEvent(99L, 2L, listOf(3L)))
        verify(exactly = 0) { service.notify(any(), any(), any()) }
    }

    @Test
    fun `waiver notifies actor`() {
        listener.onWaiver(RemediationWaiverEvent(1L, 2L, 3L, "r"))
        verify { service.notify("REMEDIATION_WAIVER", mapOf("findingId" to 2L, "reason" to "r"), listOf(3L)) }
    }

    @Test
    fun `service failure does not propagate`() {
        every { projectRepository.findById(1L) } returns Optional.of(project(7L))
        every { service.notify(any(), any(), any()) } throws RuntimeException("boom")
        listener.onRegression(FindingRegressionEvent(1L, 2L, listOf(3L)))   // 不抛 —— runCatching 兜底
        listener.onWaiver(RemediationWaiverEvent(1L, 2L, 3L, "r"))
    }

    @Test
    fun `scan completed resolves owner`() {
        every { projectRepository.findById(1L) } returns Optional.of(project(7L))
        listener.onScanCompleted(ScanCompletedEvent(1L, 1L, "SUCCESS"))
        verify { service.notify("SCAN_COMPLETED", mapOf("scanTaskId" to 1L, "status" to "SUCCESS"), listOf(7L)) }
    }

    @Test
    fun `scan completed with no owner notifies nothing`() {
        every { projectRepository.findById(1L) } returns Optional.of(project(null))
        listener.onScanCompleted(ScanCompletedEvent(1L, 1L, "FAILED"))
        verify(exactly = 0) { service.notify(any(), any(), any()) }
    }

    @Test
    fun `report snapshot generated resolves owner`() {
        every { projectRepository.findById(1L) } returns Optional.of(project(7L))
        listener.onReportSnapshotGenerated(ReportSnapshotGeneratedEvent(5L, 1L, "COMPLIANCE"))
        verify { service.notify("REPORT_SNAPSHOT_GENERATED", mapOf("snapshotId" to 5L, "snapshotType" to "COMPLIANCE"), listOf(7L)) }
    }

    @Test
    fun `report snapshot with null project notifies nothing`() {
        listener.onReportSnapshotGenerated(ReportSnapshotGeneratedEvent(5L, null, "SCAN_SUMMARY"))
        verify(exactly = 0) { service.notify(any(), any(), any()) }
    }

    @Test
    fun `remediation assigned notifies assignee`() {
        listener.onRemediationAssigned(RemediationAssignedEvent(3L, 9L, 4L))
        verify { service.notify("REMEDIATION_ASSIGNED", mapOf("findingId" to 3L), listOf(4L)) }
    }

    @Test
    fun `remediation completed notifies actor and assignee deduped`() {
        listener.onRemediationCompleted(RemediationCompletedEvent(3L, 9L, 4L, 4L))
        verify { service.notify("REMEDIATION_COMPLETED", mapOf("findingId" to 3L), listOf(4L)) }
    }

    @Test
    fun `remediation completed notifies actor and assignee`() {
        listener.onRemediationCompleted(RemediationCompletedEvent(3L, 9L, 4L, 5L))
        verify { service.notify("REMEDIATION_COMPLETED", mapOf("findingId" to 3L), listOf(4L, 5L)) }
    }
}
```

- [x] **Step 7: 写重试 Job 单测**

创建 `module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationRetryJobTest.kt`（spec §9.1：候选参数 + 分发 + 异常不杀批）：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class NotificationRetryJobTest {
    private val repository = mockk<NotificationRepository>()
    private val emailSender = mockk<EmailSender>()
    private val webhookSender = mockk<WebhookSender>()
    private val backoff = NotificationRetryBackoff(maxAttempts = 5)
    private val job = NotificationRetryJob(repository, emailSender, webhookSender, backoff)

    private fun row(channel: String) = Notification().apply {
        id = 1L; this.channel = channel; type = "SCAN_COMPLETED"; status = "FAILED"; retryCount = 1
    }

    @Test
    fun `retryFailed queries candidates and dispatches by channel`() {
        val email = row(Channel.EMAIL.name)
        val webhook = row(Channel.WEBHOOK.name)
        every { repository.findByStatusAndChannelInAndRetryCountLessThanAndNextRetryAtLessThanEqual(any(), any(), any(), any()) } returns listOf(email, webhook)
        every { emailSender.send(email) } just Runs
        every { webhookSender.send(webhook) } just Runs
        job.retryFailed()
        verify(exactly = 1) { emailSender.send(email) }
        verify(exactly = 1) { webhookSender.send(webhook) }
    }

    @Test
    fun `retryFailed uses failed email and webhook channels below max with elapsed gate`() {
        every { repository.findByStatusAndChannelInAndRetryCountLessThanAndNextRetryAtLessThanEqual(any(), any(), any(), any()) } returns emptyList()
        job.retryFailed()
        verify {
            repository.findByStatusAndChannelInAndRetryCountLessThanAndNextRetryAtLessThanEqual(
                "FAILED", listOf(Channel.EMAIL.name, Channel.WEBHOOK.name), 5, any(),
            )
        }
    }

    @Test
    fun `exception in one row does not kill the batch`() {
        val email = row(Channel.EMAIL.name)
        val webhook = row(Channel.WEBHOOK.name)
        every { repository.findByStatusAndChannelInAndRetryCountLessThanAndNextRetryAtLessThanEqual(any(), any(), any(), any()) } returns listOf(email, webhook)
        every { emailSender.send(email) } throws RuntimeException("boom")
        every { webhookSender.send(webhook) } just Runs
        job.retryFailed()   // 不抛 —— runCatching 逐行兜底（R-M18-11）
        verify(exactly = 1) { webhookSender.send(webhook) }
    }
}
```

- [x] **Step 8: 门禁**

Run: `./gradlew :module-notification:test`
Expected: BUILD SUCCESSFUL —— NotificationServiceTest 6 测试 + NotificationEventListenerTest 11 测试 + NotificationRetryJobTest 3 测试全绿，既有测试回归 0 失败。

- [x] **Step 9: 提交**

```bash
git add module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationService.kt \
        module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationEventListener.kt \
        module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationSchedulingConfig.kt \
        module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationRetryJob.kt \
        module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationServiceTest.kt \
        module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationEventListenerTest.kt \
        module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationRetryJobTest.kt
git commit -m "feat(notification): M18 T4 — notify variables signature + retry scheduling (M5/M6)"
```

---
---

### Task 5: 硬化收尾 M4 + 集成测试 + 构建门

**Files:**
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecycleService.kt`（M4：publishEvent 包 runCatching + logger）
- Test: `module-result/src/test/kotlin/com/example/compliance/result/application/FindingLifecycleServiceTest.kt`（+1 M4 测试）
- Test: `app-server/src/test/kotlin/com/example/compliance/notification/M18NotificationTemplateIntegrationTest.kt`
- Test: `app-server/src/test/kotlin/com/example/compliance/notification/M18NotificationRetryIntegrationTest.kt`

**Interfaces:**
- Consumes: 全部 T1-T4 产出（模板 API/渲染器/重试 job/事件接线）；`AbstractIntegrationTest`（app-server，共享 Testcontainers PG16）；`@WithMockUser`/`SecurityMockMvcRequestPostProcessors`（spring-security-test 已有）；`StubJavaMailSender`/`StubWebhookClient`（镜像 M17Delivery 同类）。
- Produces: M4 硬化（发布侧 runCatching 双保险统一）；两个集成测试类验证端到端（模板渲染切换 + 重试闭环 + RBAC 正负例）。

- [x] **Step 1: M4 —— FindingLifecycleService 发布侧 runCatching**

修改 `module-result/.../application/FindingLifecycleService.kt`（spec §6 M4）：
1. 类声明上方（`class FindingLifecycleService` 前）加字段（紧邻 `eventPublisher` 参数后）：

```kotlin
    private val log = org.slf4j.LoggerFactory.getLogger(FindingLifecycleService::class.java)
```

（或加 `import org.slf4j.LoggerFactory` 后写 `private val log = LoggerFactory.getLogger(FindingLifecycleService::class.java)`。）

2. 把 `verifyRechecking` 尾部（当前第 78-80 行）：

```kotlin
        if (regressedIds.isNotEmpty()) {
            eventPublisher.publishEvent(FindingRegressionEvent(projectId, scanTaskId, regressedIds))
        }
```

替换为：

```kotlin
        if (regressedIds.isNotEmpty()) {
            // M18 (M4): 发布侧 runCatching 双保险统一 —— 事件发布失败仅日志，不阻断 verifyRechecking
            runCatching { eventPublisher.publishEvent(FindingRegressionEvent(projectId, scanTaskId, regressedIds)) }
                .onFailure { log.warn("publish finding regression event failed: {}", it.message) }
        }
```

- [x] **Step 2: M4 测试 —— publishEvent 抛异常不阻断验证流程**

在 `module-result/src/test/kotlin/com/example/compliance/result/application/FindingLifecycleServiceTest.kt` 末尾追加测试（镜像既有 `verifyRechecking closes absent and regresses present findings` 的 mock 装配）：

```kotlin
    @Test
    fun `verifyRechecking survives publish event failure`() {
        // M4：事件发布抛异常（publisher 故障）→ runCatching 吞掉，验证流程不受影响（best-effort 双保险）
        val fixed1 = Finding().apply { id = 1L; projectId = 5L; status = FindingStatus.RECHECKING; fingerprint = "f1" }
        every { findingRepository.findAll() } returns listOf(fixed1)
        every { findingRepository.save(any()) } answers { firstArg() }
        every { findingRepository.findById(any()) } answers { firstArg<Long>().let { id ->
            java.util.Optional.of(listOf(fixed1).first { it.id == id })
        } }
        every { statusRepository.save(any()) } answers { firstArg() }
        every { eventPublisher.publishEvent(any<Any>()) } throws RuntimeException("publish down")

        val result = service.verifyRechecking(5L, 99L, presentFindingIds = setOf(1L), targetFindingIds = setOf(1L))

        assertEquals(VerifyResult(closed = 0, regressed = 1), result)
        assertEquals(FindingStatus.CONFIRMED, fixed1.status)
    }
```

- [x] **Step 3: 门禁 —— module-result**

Run: `./gradlew :module-result:test`
Expected: BUILD SUCCESSFUL —— FindingLifecycleServiceTest 全绿（含新 M4 测试），既有测试回归 0 失败。

- [x] **Step 4: 写模板集成测试**

创建 `app-server/src/test/kotlin/com/example/compliance/notification/M18NotificationTemplateIntegrationTest.kt`（完整 SecurityConfig + 真实模板服务/渲染器 + 事件触发；数据前缀 M18TMP-*/M18NTF-*；PL-M18-3 单方法内发布→断言→disable→断言）：

```kotlin
package com.example.compliance.notification

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.common.event.ScanCompletedEvent
import com.example.compliance.notification.domain.TemplateStatus
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.example.compliance.notification.infrastructure.NotificationTemplateRepository
import com.example.compliance.notification.infrastructure.NotificationTemplateVersionRepository
import com.example.compliance.project.domain.Project
import com.example.compliance.project.infrastructure.ProjectRepository
import com.example.compliance.user.domain.User
import com.example.compliance.user.infrastructure.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M18 模板 e2e：完整 SecurityConfig + 真实模板服务/渲染器 + 事件触发。数据前缀 M18TMP-*/M18NTF-*。
 *  共享容器安全（PL-M18-3）：自定义 SCAN_COMPLETED 模板只在「custom template」方法内 PUBLISHED，
 *  方法结束时 disable → 回落内置（内置文本 == V15 播种文本，M17Event 等对默认标题的断言在任何顺序下都成立）。 */
@AutoConfigureMockMvc
class M18NotificationTemplateIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var publisher: ApplicationEventPublisher
    @Autowired lateinit var repository: NotificationRepository
    @Autowired lateinit var templateRepository: NotificationTemplateRepository
    @Autowired lateinit var versionRepository: NotificationTemplateVersionRepository
    @Autowired lateinit var projectRepository: ProjectRepository
    @Autowired lateinit var userRepository: UserRepository

    private fun ownerUser(username: String): Long =
        userRepository.save(User().apply { this.username = username; passwordHash = "x" }).id!!

    private fun project(code: String, ownerId: Long): Long =
        projectRepository.save(Project().apply { this.code = code; name = "m18"; ownerUserId = ownerId }).id!!

    @Test
    @WithMockUser(username = "m18tmp-admin", roles = ["ADMIN"])
    fun `published custom template drives rendering and disable falls back to built-in`() {
        val owner = ownerUser("m18tmp-owner1")
        val projectId = project("M18TMP1", owner)

        // 发布自定义 SCAN_COMPLETED 模板（draft → publish，v2）
        mockMvc.perform(
            post("/api/v1/notification-templates/SCAN_COMPLETED/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"m18 custom","content":{"title":"M18 custom","body":"任务 {scanTaskId} 完成状态 {status}"}}""")
        ).andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/notification-templates/SCAN_COMPLETED/publish"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.versionNo").value(2))

        // 触发扫描完成事件 → IN_APP 行按自定义模板渲染
        publisher.publishEvent(ScanCompletedEvent(1001L, projectId, "SUCCESS"))
        val custom = repository.findByRecipient(owner.toString()).filter { it.type == "SCAN_COMPLETED" }.maxByOrNull { it.id!! }!!
        assertEquals("M18 custom", custom.title)
        assertEquals("任务 1001 完成状态 SUCCESS", custom.content)

        // disable → 再触发 → 内置回落（title/body = M17 逐字默认）
        mockMvc.perform(post("/api/v1/notification-templates/SCAN_COMPLETED/disable"))
            .andExpect(status().isOk)
        publisher.publishEvent(ScanCompletedEvent(1002L, projectId, "SUCCESS"))
        val fallback = repository.findByRecipient(owner.toString()).filter { it.type == "SCAN_COMPLETED" }.maxByOrNull { it.id!! }!!
        assertEquals("scan completed", fallback.title)
        assertEquals("扫描 1002 完成：SUCCESS", fallback.content)

        // 共享容器安全：结束时 SCAN_COMPLETED 无活跃 PUBLISHED（禁用态 → 后续测试走内置回落，默认文本不变）
        val templateId = templateRepository.findByTemplateType("SCAN_COMPLETED")!!.id!!
        assertTrue(versionRepository.findByTemplateIdOrderByVersionNoDesc(templateId).none { it.status == TemplateStatus.PUBLISHED })
    }

    @Test
    fun `default content renders before any customization`() {
        val owner = ownerUser("m18tmp-owner2")
        val projectId = project("M18TMP2", owner)
        publisher.publishEvent(ScanCompletedEvent(1003L, projectId, "SUCCESS"))
        val row = repository.findByRecipient(owner.toString()).filter { it.type == "SCAN_COMPLETED" }.maxByOrNull { it.id!! }!!
        // 播种 PUBLISHED 或禁用回落 → 文本同为默认（内置 == 播种文本，本断言对两种状态都成立）
        assertEquals("scan completed", row.title)
        assertEquals("扫描 1003 完成：SUCCESS", row.content)
    }

    @Test
    fun `unauthenticated draft is 401`() {
        mockMvc.perform(
            post("/api/v1/notification-templates/SCAN_COMPLETED/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":{"title":"t","body":"b"}}""")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(username = "m18tmp-dev", roles = ["DEVELOPER"])
    fun `non privileged role draft is 403`() {
        mockMvc.perform(
            post("/api/v1/notification-templates/SCAN_COMPLETED/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":{"title":"t","body":"b"}}""")
        ).andExpect(status().isForbidden)
    }
}
```

- [x] **Step 5: 写重试集成测试**

创建 `app-server/src/test/kotlin/com/example/compliance/notification/M18NotificationRetryIntegrationTest.kt`（stub 渠道首投失败 → 退避门拨回 → 手动触发 job → 重试成功；达上限不入候选；PL-M18-4 独立上下文）：

```kotlin
package com.example.compliance.notification

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.common.event.ScanCompletedEvent
import com.example.compliance.notification.application.NotificationRetryJob
import com.example.compliance.notification.application.WebhookClient
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.example.compliance.project.domain.Project
import com.example.compliance.project.infrastructure.ProjectRepository
import com.example.compliance.user.domain.User
import com.example.compliance.user.infrastructure.UserRepository
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.TestPropertySource
import java.io.InputStream
import java.time.Instant
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M18 投递重试 e2e：首投失败（stub 渠道）→ FAILED+retryCount+next_retry_at → 手动触发 job.retryFailed()
 *  （退避门 next_retry_at 拨回过去，避免等 fixedDelay，spec §9.3）→ 重试成功 SENT。
 *  PL-M18-4：@TestPropertySource 独立上下文；fixed-delay-ms 放大 1h —— @Scheduled 初始 run 在上下文启动时
 *  （无 FAILED 行 → no-op），1h 内不会自动触发干扰，只走手动触发。数据前缀 M18RTY1/2、用户 m18rty-*。 */
@TestPropertySource(properties = [
    "compliance.notification.webhook-url=http://webhook.test/retry",
    "compliance.notification.retry.fixed-delay-ms=3600000",
])
class M18NotificationRetryIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var publisher: ApplicationEventPublisher
    @Autowired lateinit var repository: NotificationRepository
    @Autowired lateinit var retryJob: NotificationRetryJob
    @Autowired lateinit var projectRepository: ProjectRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var mailStub: StubJavaMailSender
    @Autowired lateinit var webhookStub: StubWebhookClient

    @BeforeEach
    fun resetStubs() {
        mailStub.sent.clear(); mailStub.failWith = null
        webhookStub.posts.clear(); webhookStub.failWith = null
    }

    private fun ownerUser(username: String, email: String): Long =
        userRepository.save(User().apply { this.username = username; this.email = email; passwordHash = "x" }).id!!

    private fun project(code: String, ownerId: Long): Long =
        projectRepository.save(Project().apply { this.code = code; name = "m18"; ownerUserId = ownerId }).id!!

    private fun webhookRows(type: String) = repository.findByRecipient("webhook").filter { it.type == type }

    @Test
    fun `failed delivery is retried to sent once backoff elapsed`() {
        webhookStub.failWith = RuntimeException("http down")
        val owner = ownerUser("m18rty-owner1", "m18rty-owner1@example.com")
        val projectId = project("M18RTY1", owner)

        publisher.publishEvent(ScanCompletedEvent(2001L, projectId, "SUCCESS"))

        // 首投失败：WEBHOOK 行 FAILED + retryCount=1 + next_retry_at 已排程（EMAIL 行同样 FAILED 但 next_retry_at 未到）
        val failed = webhookRows("SCAN_COMPLETED").maxByOrNull { it.id!! }!!
        assertEquals("FAILED", failed.status)
        assertEquals(1, failed.retryCount)
        assertTrue(failed.nextRetryAt != null)
        assertTrue(!failed.errorMessage.isNullOrBlank())

        // 退避已到：把 next_retry_at 拨回过去（模拟 60s 流逝）；渠道恢复
        failed.nextRetryAt = Instant.now().minusSeconds(10)
        repository.save(failed)
        webhookStub.failWith = null

        retryJob.retryFailed()

        val retried = webhookRows("SCAN_COMPLETED").maxByOrNull { it.id!! }!!
        assertEquals("SENT", retried.status)
        assertNull(retried.errorMessage)
        assertNull(retried.nextRetryAt)
        assertEquals(1, retried.retryCount)   // 成功不清 retryCount（M17 语义）
        assertEquals(1, webhookStub.posts.size)
        assertTrue(webhookStub.posts.single().body.contains("\"recipientIds\":[\"$owner\"]"))
    }

    @Test
    fun `row at max attempts is not re-candidated`() {
        webhookStub.failWith = RuntimeException("http down")
        val owner = ownerUser("m18rty-owner2", "m18rty-owner2@example.com")
        val projectId = project("M18RTY2", owner)

        publisher.publishEvent(ScanCompletedEvent(2002L, projectId, "SUCCESS"))

        val webhookRow = webhookRows("SCAN_COMPLETED").maxByOrNull { it.id!! }!!
        // 预置到第 4 次失败（maxAttempts 默认 5）且退避已到
        webhookRow.retryCount = 4
        webhookRow.nextRetryAt = Instant.now().minusSeconds(10)
        repository.save(webhookRow)

        retryJob.retryFailed()   // 第 5 次失败 → 达上限

        val capped = webhookRows("SCAN_COMPLETED").maxByOrNull { it.id!! }!!
        assertEquals("FAILED", capped.status)
        assertEquals(5, capped.retryCount)
        assertNull(capped.nextRetryAt)
        assertTrue(capped.errorMessage!!.contains("max attempts reached"))

        val postsAfterCap = webhookStub.posts.size
        retryJob.retryFailed()   // 不再候选（retryCount 5 !< 5）
        assertEquals(postsAfterCap, webhookStub.posts.size)   // 未再投递
        assertEquals("FAILED", webhookRows("SCAN_COMPLETED").maxByOrNull { it.id!! }!!.status)
    }

    @TestConfiguration
    class RetryStubConfig {
        @Bean
        @Primary
        fun mailSender(): JavaMailSender = StubJavaMailSender()

        @Bean
        @Primary
        fun webhookClient(): WebhookClient = StubWebhookClient()
    }

    class StubJavaMailSender : JavaMailSender {
        val sent = mutableListOf<MimeMessage>()
        var failWith: RuntimeException? = null
        override fun createMimeMessage(): MimeMessage = MimeMessage(jakarta.mail.Session.getInstance(Properties()))
        override fun createMimeMessage(contentStream: InputStream): MimeMessage =
            MimeMessage(jakarta.mail.Session.getInstance(Properties()), contentStream)
        override fun send(mimeMessage: MimeMessage) { failWith?.let { throw it }; sent += mimeMessage }
        override fun send(vararg mimeMessages: MimeMessage) { mimeMessages.forEach { send(it) } }
        override fun send(vararg simpleMessages: SimpleMailMessage) { }
    }

    class StubWebhookClient : WebhookClient {
        val posts = mutableListOf<Post>()
        var failWith: RuntimeException? = null
        data class Post(val url: String, val body: String)
        override fun post(url: String, body: String): Boolean {
            failWith?.let { throw it }
            posts += Post(url, body)
            return true
        }
    }
}
```

- [x] **Step 6: 门禁 —— 两个集成测试类**

Run: `./gradlew :app-server:test --tests "com.example.compliance.notification.M18NotificationTemplateIntegrationTest" --tests "com.example.compliance.notification.M18NotificationRetryIntegrationTest"`
Expected: 两个测试类全绿（模板 4 测试 + 重试 2 测试）。

- [x] **Step 7: 全量构建门**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL —— 全量测试（既有 298 + 本里程碑新增单元/切片/集成）0 失败。

- [x] **Step 8: 提交**

```bash
git add module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecycleService.kt \
        module-result/src/test/kotlin/com/example/compliance/result/application/FindingLifecycleServiceTest.kt \
        app-server/src/test/kotlin/com/example/compliance/notification/M18NotificationTemplateIntegrationTest.kt \
        app-server/src/test/kotlin/com/example/compliance/notification/M18NotificationRetryIntegrationTest.kt
git commit -m "feat(notification): M18 T5 — M4 publish hardening + template/retry integration tests"
```
