# M18 通知服务完整化（模板化 + 投递重试 + 硬化收尾）设计

## 1. 背景与目标

M17 已交付通知服务基础：站内信中心（读态 API）+ EMAIL/Webhook 真实渠道投递 + 扫描/报告/整改全事件接线，全部 best-effort（通知失败绝不影响发布方）。M17 明确延期的两项能力与终审记录的多条硬化候选，构成本里程碑：

- **模板化**：当前 `NotificationEventListener` 的标题/正文全部硬编码在代码里（6 处）。平台架构原则要求「合规清单、规则、报告模板必须支持版本化」—— 通知模板加入版本化行列。
- **投递重试**：EMAIL/WEBHOOK 失败后停留在 FAILED，无任何后台重试（M17 计划 Global Constraints 原文「无后台重试（归 M18 Quartz）」；终审已裁定改为 Spring @Scheduled）。
- **硬化收尾**：M1（WebhookSender 序列化入 try）、M4（module-result 发布侧 runCatching 双保险统一）、M5（listMy 分页守卫单测）、M6（notify 微优化）。

目标：M18 之后，通知内容的呈现由**版本化模板**驱动（管理员可发布自定义模板，缺失时回落内置），EMAIL/WEBHOOK 投递具备**指数退避重试**与最大次数上限，M17 终审硬化项全部落地。

## 2. 范围

### 2.1 纳入（用户已确认）

1. **通知模板化（完整版本化，镜像 module-report 模板范式）**：`NotificationTemplate` 主线 + `NotificationTemplateVersion` 历史，draft/publish/disable/versions 生命周期，publish 审计，V15 播种 6 类型默认 PUBLISHED 模板，渲染缺 PUBLISHED 时回落内置。
2. **投递重试调度（Spring @Scheduled）**：FAILED 的 EMAIL/WEBHOOK 行定时重试，指数退避 + 最大次数上限。
3. **硬化收尾**：M1 / M4 / M5 / M6。

### 2.2 非目标（明确不做，防蔓延）

- WECHAT/DINGTALK 渠道仍为枚举值，不实现（短信/IM 渠道归后续里程碑）。
- 多实例调度竞争安全（平台为单实例 modular monolith；多实例部署时需迁移持久化调度器 —— 留 note，归未来）。
- 通知类型扩展：6 类型固定；新增类型走「迁移播种 + 内置回落」机制，无需改代码逻辑。
- 模板软删除/审计回滚（沿用 report 先例的 disable 语义，不做 destroy）。

## 3. 总体架构

模块化单体；本里程碑改动集中在 **module-notification**（模板 + 调度 + notify 签名），外加 module-result 一行硬化（M4）与 app-server（V15 + 集成测试）。**无 SecurityConfig 改动；发布方（module-scan/report/remediation/result）零新依赖边；通知模块零新外部依赖**（`@EnableScheduling`/`@Scheduled` 为 Spring Boot 内建，`AuditService` 在 module-common 已有依赖）。

### 3.1 组件总览（module-notification）

```
domain/Notification.kt                     # + recipient_ids / occurred_at / next_retry_at 三列
domain/NotificationTemplate.kt             # 新建：模板主线
domain/NotificationTemplateVersion.kt      # 新建：模板版本历史
domain/TemplateStatus.kt                   # 新建：本地枚举 { DRAFT, PUBLISHED, DISABLED }
infrastructure/NotificationRepository.kt   # + 重试候选查询
infrastructure/NotificationTemplateRepository.kt         # 新建
infrastructure/NotificationTemplateVersionRepository.kt  # 新建
application/NotificationService.kt         # notify 签名改 notify(type, variables, recipients)；渲染接入
application/TemplateRenderer.kt            # 新建：{var} 占位符渲染，缺失回落内置
application/DefaultNotificationTemplates.kt              # 新建：6 类型内置默认（= V15 播种同文，Kotlin 常量）
application/NotificationTemplateService.kt # 新建：draft/publish/disable/versions
application/NotificationRetryBackoff.kt    # 新建：失败策略（指数退避 + max 上限）
application/NotificationRetryJob.kt        # 新建：@Scheduled 轮询重试
application/NotificationSchedulingConfig.kt              # 新建：@EnableScheduling
application/EmailSender.kt                 # fail() → NotificationRetryBackoff.onFailure；成功清 next_retry_at
application/WebhookSender.kt               # send(row) 从行读 recipientIds/occurredAt；序列化入 try（M1）
application/NotificationEventListener.kt   # 6 handler 改传语义变量（notify 新签名唯一调用方）
api/NotificationTemplateController.kt      # 新建：/api/v1/notification-templates
api/NotificationTemplateDtos.kt            # 新建：DraftRequest / TemplateVersionView
```

### 3.2 关键架构裁定（本 spec 锁定）

| Ruling | 内容 | 理由 |
|---|---|---|
| **R-M18-1** | 调度器用 Spring `@Scheduled`（fixedDelayString 属性驱动），不引 Quartz | 用户选定；单实例重试任务简单，零依赖、零 Quartz schema 表；后续需要持久化 job store 时再迁 |
| **R-M18-2** | 模板完整版本化，镜像 module-report `ReportTemplate`/`ReportTemplateVersion` | 用户选定；平台原则「模板必须支持版本化」 |
| **R-M18-3** | `TemplateStatus { DRAFT, PUBLISHED, DISABLED }` 本地枚举放 module-notification，不复用 module-checklist 的 `VersionStatus` | notification 目前无 checklist 依赖；为一个枚举加依赖边不值；值语义相同 |
| **R-M18-4** | `notify` 签名改为 `notify(notificationType: String, variables: Map<String, Any?>, recipients: List<Long>)`，渲染在 NotificationService 内 | 唯一调用方是 `NotificationEventListener`（同步改）；呈现归通知模块，发布方只传语义变量 |
| **R-M18-5** | WEBHOOK 行的 `recipientIds`（逗号串）与 `occurredAt` 持久化到行上；`WebhookSender.send(row)` 从行读，不再从调用上下文传 | M17 的 `send(row, recipientIds, occurredAt)` 落库后重试无法重建 payload；状态归行使首投与重试同路径 |
| **R-M18-6** | 重试策略：候选 `status='FAILED' AND retryCount<max AND next_retry_at<=now`；指数退避 `2^(retryCount-1)` 分钟；`maxAttempts` 默认 5（属性 `compliance.notification.retry.max-attempts`）；初始失败与重试失败统一走 `NotificationRetryBackoff.onFailure` | 单点策略，sender 保持哑适配器 |
| **R-M18-7** | 渲染失败/缺失 → 回落内置默认（M17 现有字符串原样），**绝不抛出** | best-effort 红线延续：通知内容问题不得导致通知失败或影响发布方 |
| **R-M18-8** | 占位符缺失变量 → 替换为空串；未知类型（无模板且无内置）→ title=body=type | 避免 `{x}` 字面泄漏；6 类型固定下未知类型为退化情形 |
| **R-M18-9** | publish 写审计 `NOTIFICATION_TEMPLATE_PUBLISHED`（复用 module-common `AuditService`） | AuditService 在 module-common，notification 已有依赖，零新边 |
| **R-M18-10** | 模板管理 API 方法级 `@PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_MANAGER')")` | 沿用 `RemediationController:71` 方法级范式；**不碰 SecurityConfig** |
| **R-M18-11** | 重试 job 不设外层事务；sender 各自落库（auto-commit），逐行 runCatching 兜底 | 单行失败不影响整批；sender 本就不抛（M17），runCatching 为双保险 |

## 4. 通知模板子系统

### 4.1 实体（V15 新建两表）

**`NotificationTemplate`**（主线，每类型一条）—— 镜像 `ReportTemplate`：

```kotlin
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

**`NotificationTemplateVersion`**（历史）—— 镜像 `ReportTemplateVersion`，content 为 JSONB `{"title": "...", "body": "..."}`：

```kotlin
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

**`TemplateStatus`**（module-notification `domain` 包）：
```kotlin
enum class TemplateStatus { DRAFT, PUBLISHED, DISABLED }
```

### 4.2 通知类型集（固定 6 类型）

`SCAN_COMPLETED` / `REPORT_SNAPSHOT_GENERATED` / `REMEDIATION_ASSIGNED` / `REMEDIATION_COMPLETED` / `FINDING_REGRESSION` / `REMEDIATION_WAIVER`（与 M17 一致）。

### 4.3 默认模板（V15 播种 + 内置回落，同文）

6 条默认 PUBLISHED 模板（version_no=1，内容 = M17 现有硬编码标题/正文，占位符化）：

| type | title | body |
|---|---|---|
| SCAN_COMPLETED | `scan completed` | `扫描 {scanTaskId} 完成：{status}` |
| REPORT_SNAPSHOT_GENERATED | `report snapshot generated` | `快照 {snapshotId} 已生成（{snapshotType}）` |
| REMEDIATION_ASSIGNED | `remediation assigned` | `finding {findingId} 已指派给你` |
| REMEDIATION_COMPLETED | `remediation completed` | `finding {findingId} 已标记完成` |
| FINDING_REGRESSION | `finding regressed` | `回归：{findingCount} 个 finding 在扫描 {scanTaskId} 复现` |
| REMEDIATION_WAIVER | `finding waived` | `finding {findingId} 被豁免（{reason}）` |

> FINDING_REGRESSION 正文从 M17 的 `"回归：${e.findingIds.size} 个 finding 在扫描 ${e.scanTaskId} 复现"` 迁移为变量 `{findingCount}`（监听器传 `findingIds.size`）。其余 5 条与 M17 逐字一致。

**`DefaultNotificationTemplates`**（Kotlin 常量，渲染回落用，与 V15 播种同文）：
```kotlin
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

### 4.4 渲染（TemplateRenderer）

```kotlin
@Component
class TemplateRenderer(
    private val templateRepository: NotificationTemplateRepository,
    private val versionRepository: NotificationTemplateVersionRepository,
) {
    private val objectMapper = ObjectMapper()

    /** 解析 type 的 PUBLISHED 模板渲染 title/body；无 PUBLISHED/解析失败 → 内置回落。绝不抛出。 */
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

规则：
- 有 PUBLISHED 版本 → 用模板内容；content JSON 解析失败（理论不发生）→ 回落内置（R-M18-7）。
- 无 PUBLISHED（未播种/被 disable/从未 draft）→ 内置回落。
- 未知类型（无模板且无内置）→ title=body=type（R-M18-8 退化分支）。
- 占位符 `{key}`：`variables` 中存在的键替换为字符串值；缺失键 → 空串。

### 4.5 服务（NotificationTemplateService，镜像 ReportTemplateService）

```kotlin
@Service
class NotificationTemplateService(
    private val templateRepository: NotificationTemplateRepository,
    private val versionRepository: NotificationTemplateVersionRepository,
    private val auditService: AuditService,
) {
    private val objectMapper = ObjectMapper()

    companion object {
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

行为要点（镜像 report 先例，逐条对应）：
- `draft`：新建类型首版或复用打开的 DRAFT；DRAFT 打开期间多次 draft 覆盖同一版（不递增 versionNo）。
- `publish`：要求存在 DRAFT，否则 400；发布前既有 PUBLISHED 全降级 DISABLED（单一活跃版）；审计 `NOTIFICATION_TEMPLATE_PUBLISHED`。
- `disable`：停用活跃 PUBLISHED（镜像 R-M12-6：最新打开中的 DRAFT 无视之）；无活跃 PUBLISHED → 400。
- `versions`：按 versionNo 倒序。

### 4.6 渲染接入点：notify 签名变化

**M17**：`notify(notificationType: String, title: String, body: String, recipients: List<Long>)`
**M18**：`notify(notificationType: String, variables: Map<String, Any?>, recipients: List<Long>)`

`NotificationService` 注入 `TemplateRenderer`；`notify` 开头渲染一次 `val (title, body) = renderer.render(notificationType, variables)`（所有渠道/收件人共用同一内容）。M6 同步落实：`emailSender.isAvailable()` 提升出循环。

`NotificationEventListener` 6 个 handler 全部改为传语义变量：

```kotlin
onScanCompleted:         notify("SCAN_COMPLETED", mapOf("scanTaskId" to e.scanTaskId, "status" to e.status), listOf(owner))
onReportSnapshotGenerated: notify("REPORT_SNAPSHOT_GENERATED", mapOf("snapshotId" to e.snapshotId, "snapshotType" to e.snapshotType), listOf(owner))
onRemediationAssigned:   notify("REMEDIATION_ASSIGNED", mapOf("findingId" to e.findingId), listOf(e.assigneeId))
onRemediationCompleted:  notify("REMEDIATION_COMPLETED", mapOf("findingId" to e.findingId), listOfNotNull(e.actorId, e.assigneeId).distinct())
onRegression:            notify("FINDING_REGRESSION", mapOf("findingCount" to e.findingIds.size, "scanTaskId" to e.scanTaskId), listOf(owner))
onWaiver:                notify("REMEDIATION_WAIVER", mapOf("findingId" to e.findingId, "reason" to e.reason), listOf(e.actorId))
```

发布方（module-scan/report/remediation/result）不调 notify，**零改动**。

### 4.7 API（NotificationTemplateController）

`/api/v1/notification-templates/{type}/draft|publish|disable|versions`，方法级 `@PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_MANAGER')")`（R-M18-10，无 SecurityConfig 改动）：

```kotlin
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

DTO（`NotificationTemplateDtos.kt`，镜像 ReportTemplateDtos）：
```kotlin
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

## 5. 投递重试（Spring @Scheduled）

### 5.1 调度启用

`NotificationSchedulingConfig`（module-notification）：
```kotlin
@Configuration
@EnableScheduling
class NotificationSchedulingConfig
```

### 5.2 实体补列（V15 ALTER，配合重试）

`Notification` 增三列（与 V14 的 read_at/error_message 风格一致，TIMESTAMP 非 TIMESTAMPTZ 对齐 V11）：
```kotlin
@Column(name = "recipient_ids")
var recipientIds: String? = null          // WEBHOOK payload 重建：逗号串 userIds
@Column(name = "occurred_at")
var occurredAt: Instant? = null           // WEBHOOK payload 重建：事件发生时刻
@Column(name = "next_retry_at")
var nextRetryAt: Instant? = null          // 指数退避下次重试门
```

### 5.3 失败策略（NotificationRetryBackoff，单一策略点）

```kotlin
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

退避序列（maxAttempts=5）：第 1 次失败 → 60s 后重试，第 2 次 → 120s，第 3 次 → 240s，第 4 次 → 480s，第 5 次失败达上限 → 标记 `max attempts reached`，不再被候选。

### 5.4 Sender 改动

**EmailSender**（`fail()` 替换为 `retryBackoff.onFailure(row, msg)`；成功路径清 `errorMessage`/`nextRetryAt`）：
- 注入 `NotificationRetryBackoff`。
- 成功：`row.status = "SENT"; row.sentAt = now; row.errorMessage = null; row.nextRetryAt = null; repository.save(row)`。
- 失败（catch）：`retryBackoff.onFailure(row, e.message); repository.save(row)`（尾部 save 保留，M17 语义）。
- `isAvailable()` 逻辑不变。

**WebhookSender**（`send(row: Notification)` 从行读 payload 字段；序列化入 try = M1）：
- 签名 `send(row: Notification)`（R-M18-5），不再接收 `recipientIds`/`occurredAt` 参数。
- payload 从行读：`{"type": row.type, "title": row.title, "body": row.content, "recipientIds": row.recipientIds?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList(), "occurredAt": row.occurredAt?.toString()}`。
- `objectMapper.writeValueAsString(payload)` **移入 try 块内**（M1：全基本类型 map 理论不抛，但 write 属可失败 IO 类操作，隔离成立）。
- 失败 catch：`retryBackoff.onFailure(row, e.message); repository.save(row)`。
- `isConfigured()` 逻辑不变。

### 5.5 重试 Job（NotificationRetryJob）

```kotlin
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

- 不设外层 `@Transactional`（R-M18-11）：sender 各自落库，单行隔离。
- `@Scheduled(fixedDelayString = "\${compliance.notification.retry.fixed-delay-ms:60000}")`：属性可配，默认 60s。
- 并发安全：单实例 + @Scheduled 默认单线程 + `next_retry_at` 门；多实例部署时多实例会重复拉取候选 → 未来迁移持久化调度器（§2.2 note）。

### 5.6 数据流（重试）

```
notify 创建 EMAIL/WEBHOOK 行（status=PENDING，WEBHOOK 行含 recipient_ids/occurred_at）
  → sender 立即尝试：成功 → SENT + sentAt（清 errorMessage/next_retry_at）
                    失败 → retryBackoff.onFailure：FAILED + retryCount+1 + errorMessage + next_retry_at
  → NotificationRetryJob 每 60s：候选 = FAILED 且 retryCount<max 且 next_retry_at<=now
       → 逐行 sender.send(row)（EMAIL 读 recipient；WEBHOOK 读 recipient_ids/occurred_at 重建 payload）
       → 成功 SENT；失败继续 onFailure（retryCount 递增，退避翻倍）
  → retryCount 达 maxAttempts → 标记 max attempts reached，不再入候选
```

## 6. 硬化收尾

| 项 | 内容 | 位置 |
|---|---|---|
| **M1** | `WebhookSender` 的 `objectMapper.writeValueAsString` 移入 try/catch 内 | module-notification `WebhookSender.kt` |
| **M4** | `FindingLifecycleService.kt:79` 的 `publishEvent(FindingRegressionEvent(...))` 包 `runCatching`（双保险统一；该服务需 `LoggerFactory`） | module-result `FindingLifecycleService.kt` |
| **M5** | `NotificationServiceTest` 补 `listMy(-1, ...)` → `BusinessException(400, "page must be non-negative")` 直接单测 | module-notification 测试 |
| **M6** | `notify` 内 `emailSender.isAvailable()` 提升出循环（每事件评估一次） | module-notification `NotificationService.kt` |

M4 具体改动：
```kotlin
if (regressedIds.isNotEmpty()) {
    runCatching { eventPublisher.publishEvent(FindingRegressionEvent(projectId, scanTaskId, regressedIds)) }
        .onFailure { log.warn("publish finding regression event failed: {}", it.message) }
}
```
新增测试：publishEvent 抛异常 → `verifyRechecking` 仍正常返回结果（不向调用方抛）。

## 7. DDL（V15，唯一授权）

`app-server/src/main/resources/db/migration/V15__notification_template_retry.sql`（镜像 V13 模板表 DDL；ALTER 对齐 V11 TIMESTAMP 风格）：

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

## 8. RBAC

| 端点 | 角色 |
|---|---|
| `GET/POST /api/v1/notifications/**`（站内信中心，M17 既有） | `isAuthenticated()`（不变） |
| `/api/v1/notification-templates/{type}/draft\|publish\|disable` | `ADMIN` / `COMPLIANCE_MANAGER`（方法级 @PreAuthorize） |
| `/api/v1/notification-templates/{type}/versions` | `ADMIN` / `COMPLIANCE_MANAGER`（方法级 @PreAuthorize） |

无 SecurityConfig 改动。authorities 形如 `ROLE_<code>`（`SecurityConfig.kt:46` 先例），`hasAnyRole('ADMIN','COMPLIANCE_MANAGER')` 匹配 `ROLE_ADMIN`/`ROLE_COMPLIANCE_MANAGER`。

## 9. 测试策略

### 9.1 单元（module-notification）

| 测试类 | 覆盖 |
|---|---|
| `TemplateRendererTest` | 占位符替换（含中文正文）；缺失变量 → 空串；无 PUBLISHED → 内置回落；未知类型 → 回落 type；有 PUBLISHED → 用模板内容 |
| `NotificationTemplateServiceTest` | draft 新建类型首版 / 复用打开 DRAFT 不递增 versionNo / 未知类型 400；publish 降级旧 PUBLISHED（单一活跃）+ 审计 verify + 无 DRAFT 400；disable 停活跃 PUBLISHED + 无活跃 400；versions 倒序 |
| `NotificationRetryBackoffTest` | 退避序列（retryCount=1 → 60s …）；达 max → nextRetryAt=null + `max attempts reached` |
| `NotificationRetryJobTest` | 候选查询参数（FAILED + EMAIL/WEBHOOK + <max + <=now）；EMAIL/WEBHOOK 分发；异常行不杀批 |
| `NotificationServiceTest` | 更新为变量签名：渲染断言（stub renderer 或注入真 renderer 的仓库 mock）；isAvailable 提升；**M5：page<0 → BusinessException(400)** |
| `EmailSenderTest` / `WebhookSenderTest` | 注入 RetryBackoff；成功清 errorMessage/nextRetryAt；失败走 onFailure；**WebhookSender：send(row) 从行读 recipientIds/occurredAt 重建 payload；序列化异常 → onFailure（M1）** |

### 9.2 切片（module-notification）

`NotificationTemplateControllerTest`：4 端点（draft/publish/disable/versions）—— 角色门控（ADMIN/COMPLIANCE_MANAGER 200，无角色 403）与业务路径。

### 9.3 集成（app-server）

| 测试类 | 覆盖 |
|---|---|
| `M18NotificationTemplateIntegrationTest` | 发布自定义模板（draft+publish 新正文）→ 触发扫描完成事件 → 站内信按新模板渲染（标题/正文断言）；disable 后回落内置 |
| `M18NotificationRetryIntegrationTest` | stub 渠道首次失败（FAILED + retryCount=1 + next_retry_at）→ 手动触发 job（注入 job 直接调 `retryFailed()` 避免等 fixedDelay）→ 重试成功（SENT + retryCount 保留 + next_retry_at 清空）；达 max 上限不入候选 |
| M4 | module-result 单测（publish 抛异常不阻断 verifyRechecking） |

数据前缀沿用 M17 约定：`M18NTF-*`（模板集成）、`M18RTY-*`（重试集成）、`M18TMP-*`（模板类型）；Stub 渠道用 `@Primary`（M17 先例，无 Greenmail/MockWebServer）。

### 9.4 构建门

全量 `./gradlew build`（含既有 298 测试回归）。

## 10. 红线（延续 + 新增）

1. **发布方零新依赖边**：module-scan/report/remediation/result 不新增对 notification 依赖；M4 只在 module-result 内包 runCatching（不加依赖、不引事件）。
2. **通知模块零新外部依赖**：@Scheduled/@EnableScheduling 为 Spring Boot 内建；AuditService 在 module-common（notification 已有依赖）。
3. **`audit_log` 只增不改**：publish 写审计 `NOTIFICATION_TEMPLATE_PUBLISHED`，不改 audit_log 表。
4. **DDL 唯一授权**：仅 V15。
5. **best-effort 延续**：渲染失败/缺失 → 内置回落，绝不抛；重试逐行隔离；通知失败绝不影响发布方（M17 红线不变）。
6. **无 SecurityConfig 改动**：模板 API 方法级 `@PreAuthorize`。
7. **notify 签名变化只影响唯一调用方** `NotificationEventListener`（同步改），发布方零改动。

## 11. 里程碑原则对齐

平台架构原则逐条核对：
- 业务模块边界清晰 ✅（模板/重试全部收在 module-notification）
- 扫描引擎必须通过 Adapter 接入 ✅（不受影响）
- 合规清单、规则、报告模板必须支持版本化 ✅（**通知模板本次加入版本化行列**）
- 所有动态配置必须可审计、可回滚 ✅（publish 审计；disable 停用=回滚语义）
- 扫描任务必须异步执行 ✅（不受影响）
- 不允许业务代码硬编码合规规则 ✅（通知标题/正文不再硬编码在监听器——迁入版本化模板）

## 12. 影响面清单

| 位置 | 变更 |
|---|---|
| module-notification | +13 文件（TemplateStatus、NotificationTemplate、NotificationTemplateVersion、NotificationTemplateRepository、NotificationTemplateVersionRepository、TemplateRenderer、DefaultNotificationTemplates、NotificationTemplateService、NotificationTemplateController、NotificationTemplateDtos、NotificationRetryBackoff、NotificationRetryJob、NotificationSchedulingConfig）；改 6 文件（Notification、NotificationService、NotificationEventListener、EmailSender、WebhookSender、NotificationRepository）；既有 4 个测试类同步更新（NotificationServiceTest/EmailSenderTest/WebhookSenderTest/NotificationEventListenerTest）+ 新增 TemplateRendererTest/NotificationTemplateServiceTest/NotificationRetryBackoffTest/NotificationRetryJobTest/NotificationTemplateControllerTest |
| module-result | `FindingLifecycleService.kt` 一行（M4）+ 1 新测试 |
| app-server | V15 迁移 + 2 集成测试类 |
| module-common / module-checklist / module-report / module-scan / module-remediation | 零改动 |
| SecurityConfig | 零改动 |
