# M17 通知服务（站内信中心 + EMAIL/Webhook 投递 + 事件接线）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 M10 占位通知模块升级为真正的通知服务 —— 站内信中心 API（已读态/未读计数/标记已读）+ EMAIL/Webhook 真实渠道投递（状态机 PENDING→SENT/FAILED）+ 4 个新事件接线与回归收件人修复。

**Architecture:** 模块化单体；收件人解析集中于 module-notification（监听器做「事件→userId」，`NotificationService.notify` 做「userId→email」+ 渠道 fan-out，发布方零新依赖边）。渠道经 seam 接入（`JavaMailSender` 接口 / 自定义 `WebhookClient` 接口），测试注入 stub，不引入 Greenmail/MockWebServer。通知写入 best-effort：`REQUIRES_NEW` 独立事务 + 监听器/发布方双层 `runCatching`，失败绝不影响发布方主流程。

**Tech Stack:** Kotlin 2.0.21 / Spring Boot 3.3.5 / Spring Data JPA / Spring Mail（spring-boot-starter-mail，BOM 管版本）/ RestClient（spring-boot-starter-web 已透传）/ Flyway V14 / Testcontainers PG 16。

**Spec:** `docs/superpowers/specs/2026-09-06-code-compliance-platform-m17-design.md`（本计划为其论据，冲突以 spec 为准；下列 Ruling 为本计划对 spec 未逐字锁定处的裁定）

## Global Constraints

- **Best-effort 红线**：通知写入失败绝不回滚发布方主流程 —— `NotificationService.notify` 必须 `@Transactional(REQUIRES_NEW)`；监听器每个 handler 用 `runCatching` 兜底；**发布方 publish 也用 `runCatching` 包裹**（双保险）。`EmailSender`/`WebhookSender` 的 send 方法**绝不抛出**（所有异常捕获并置 FAILED）。
- **`audit_log` 只增不改**红线不变；无硬编码合规规则；历史扫描结果不可改。
- **DDL 唯一授权**：仅 V14（`ALTER TABLE notification ADD COLUMN read_at TIMESTAMP; ADD COLUMN error_message TEXT;`），无其他 DDL。
- **发布方零新依赖边**：事件类放 module-common；module-scan/report/remediation/result 不新增对 notification 的依赖。
- **收件人解析集中在 module-notification**（R-M17-D2 两层模型）：监听器做「事件→userId」（owner 经 `ProjectRepository.findById(projectId)?.ownerUserId`，null 跳过；assignee/actor 直用），`NotificationService.notify` 做「userId→email」（blank 跳过）+ 渠道 fan-out。
- **渠道未配置即跳过**（镜像语义）：EMAIL 无 `JavaMailSender` bean（`spring.mail.*` 未设）→ 不建 EMAIL 行；webhook-url 为空 → 不建 WEBHOOK 行。
- **状态机**：IN_APP 落库即 SENT（行本身即投递）；EMAIL/WEBHOOK persist 为 PENDING，sender 尝试后置 SENT（+sentAt）/ FAILED（+retry_count+1+error_message）。无后台重试（归 M18 Quartz）。
- **通知 type 列值**（Ruling PL-M17-5，spec 未逐字锁定）：`SCAN_COMPLETED` / `REPORT_SNAPSHOT_GENERATED` / `REMEDIATION_ASSIGNED` / `REMEDIATION_COMPLETED` / `FINDING_REGRESSION` / `REMEDIATION_WAIVER`。
- **API 红线**：`/api/v1/notifications`（避开 `/api/v1/admin/**`）；方法级 `@PreAuthorize("isAuthenticated()")`；全部按 `AuthPrincipal.userId` 过滤 IN_APP 行；无 SecurityConfig 改动。
- **测试数据**：项目 `M17DLV1/2`、`M17EVT1..4`（跨测试类全局唯一，共享容器）；用户 `m17dlv-*` / `m17evt-*`；通知 module 标记 `NTF-M17-*`。
- **Channel 枚举补 WEBHOOK**（Ruling PL-M17-4）：M10 枚举 `{IN_APP, EMAIL, WECHAT, DINGTALK}` 缺 WEBHOOK，本里程碑补上（VARCHAR(16) 容纳，无 DDL）。
- 里程碑红线（spec §5）：通知写入 best-effort 不影响发布方；`audit_log` 只增不改；无硬编码合规规则；历史扫描结果不可改；无除 V14 外的 DDL。

## 计划级裁定（写计划时对 spec 未逐字锁定处的决策）

| Ruling | 内容 |
|---|---|
| **PL-M17-1** | spec §3.4 说 ScanCompletedEvent 接线点「ScanTaskService」，但终态流转实际发生在 `ScanOrchestrator`（ScanTaskService 只有 start/cancel/list，无终态转移）。按真实代码路径接线（SUCCESS 与 FAILED 两条终态分支），发布方语义不变。 |
| **PL-M17-2** | spec §3.4 事件表 `ReportSnapshotGeneratedEvent.projectId: Long`，但 SCAN_SUMMARY 快照 `projectId` 可为 null。事件字段改 `Long?`；为 null 时监听器不解析收件人（无通知）——与「通知项目负责人」意图一致。 |
| **PL-M17-3** | spec 未明说 EMAIL 未配置的行为。镜像 WEBHOOK「未配置则跳过」：无 `JavaMailSender` bean → 不建 EMAIL 行（`EmailSender.isAvailable()` 门）。保证 app 零 mail 配置可启动。 |
| **PL-M17-4** | Channel 枚举缺 WEBHOOK（spec D4/§3.3 使用 `Channel.WEBHOOK` 但 M10 枚举无）。补上。 |
| **PL-M17-5** | 通知 type 列值见 Global Constraints（spec §3.4 只锁标题/正文，未锁 type 字符串）。 |
| **PL-M17-6** | `RemediationAssignedEvent` 仅在「首次指派（existing==null）且 assigneeUserId!=null」时发布：assign 对既有 task 不更新受让人，无受让人则无人可通知。 |
| **PL-M17-7** | app-server 集成测试需直接引用 jakarta.mail/JavaMailSender（StubJavaMailSender 实现）→ app-server `testImplementation` 增 `spring-boot-starter-mail`。 |
| **PL-M17-8** | 发布方（ScanOrchestrator/ReportGenerationService/RemediationService）publish 一律 `runCatching` 包裹 —— 监听器异常（理论上不该有）也不得影响发布方主流程（spec red line 双保险）。 |

---

### Task 1: 实体字段 + V14 迁移 + 模块依赖

**Files:**
- Modify: `module-notification/src/main/kotlin/com/example/compliance/notification/domain/Notification.kt`
- Create: `app-server/src/main/resources/db/migration/V14__notification_read_state.sql`
- Modify: `module-notification/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`

**Interfaces:**
- Consumes: 既有 `Notification` 实体（V11 表，channel/recipient/type/title/content/status/retry_count/sent_at + BaseEntity）；`compliance-kotlin-module` 插件已注入 spring-boot-starter-test/mockk/kotlin-test。
- Produces: `Notification.readAt: Instant?` / `Notification.errorMessage: String?`；module-notification 依赖 `module-user`、`module-project`、`spring-boot-starter-mail`；catalog `spring-boot-starter-mail` 条目。Task 2 的 `NotificationService.notify` 依赖 `UserRepository`（module-user）、`EmailSender`（mail）；Task 3 的监听器依赖 `ProjectRepository`（module-project）。

- [ ] **Step 1: 实体增两个可空字段**

在 `Notification.kt` 的 `sentAt` 字段之后追加：

```kotlin
    @Column(name = "read_at")
    var readAt: Instant? = null
    @Column(name = "error_message")
    var errorMessage: String? = null
```

- [ ] **Step 2: 写 V14 迁移**

创建 `app-server/src/main/resources/db/migration/V14__notification_read_state.sql`：

```sql
ALTER TABLE notification ADD COLUMN read_at TIMESTAMP;
ALTER TABLE notification ADD COLUMN error_message TEXT;
```

- [ ] **Step 3: module-notification 依赖声明**

`module-notification/build.gradle.kts` 全文改为：

```kotlin
plugins { id("compliance-kotlin-module") }

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-user"))
    implementation(project(":module-project"))
    implementation(libs.spring.boot.starter.mail)
}
```

- [ ] **Step 4: catalog 补 mail 条目**

`gradle/libs.versions.toml` 的 `[libraries]` 块内、`spring-boot-starter-actuator` 一行之后追加：

```toml
spring-boot-starter-mail = { module = "org.springframework.boot:spring-boot-starter-mail" }
```

（版本由 spring-boot-dependencies BOM 管理，无需 version.ref。）

- [ ] **Step 5: 编译验证**

Run: `./gradlew :module-notification:build`
Expected: BUILD SUCCESSFUL（实体字段 + 依赖声明编译通过）

- [ ] **Step 6: 迁移 + 校验验证（Flyway V14 + ddl-auto validate）**

Run: `./gradlew :app-server:test --tests SmokeIntegrationTest`
Expected: PASS —— Flyway 依次应用 V1..V14（含新 V14），`ddl-auto: validate` 校验 `Notification` 实体的 read_at/error_message 与 V14 列一致。

- [ ] **Step 7: Commit**

```bash
git add module-notification/src/main/kotlin/com/example/compliance/notification/domain/Notification.kt \
        app-server/src/main/resources/db/migration/V14__notification_read_state.sql \
        module-notification/build.gradle.kts \
        gradle/libs.versions.toml
git commit -m "feat(notification): M17 entity read state + V14 migration + module deps (user/project/mail) (m17)"
```

---

### Task 2: 投递核心（notify + EmailSender + Webhook 适配器 + 监听器重接线）

**Files:**
- Modify: `module-notification/src/main/kotlin/com/example/compliance/notification/domain/Channel.kt`
- Rewrite: `module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationService.kt`
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/application/EmailSender.kt`
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/application/WebhookClient.kt`
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/application/RestWebhookClient.kt`
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/application/WebhookSender.kt`
- Delete: `module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationSender.kt`
- Delete: `module-notification/src/main/kotlin/com/example/compliance/notification/application/LogNotificationSender.kt`
- Rewrite: `module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationEventListener.kt`
- Rewrite: `module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationServiceTest.kt`
- Rewrite: `module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationEventListenerTest.kt`
- Delete: `module-notification/src/test/kotlin/com/example/compliance/notification/application/LogNotificationSenderTest.kt`
- Create: `module-notification/src/test/kotlin/com/example/compliance/notification/application/EmailSenderTest.kt`
- Create: `module-notification/src/test/kotlin/com/example/compliance/notification/application/WebhookSenderTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `Notification.readAt/errorMessage` + `UserRepository`（module-user，`findById`）+ `ProjectRepository`（module-project，`findById`）+ `JavaMailSender`（spring-boot-starter-mail）。
- Produces: `NotificationService.notify(notificationType: String, title: String, body: String, recipients: List<Long>)`（REQUIRES_NEW）；`EmailSender.isAvailable()/send(Notification)`；`WebhookClient.post(url: String, body: String): Boolean`；`WebhookSender.isConfigured()/send(Notification, List<Long>, Instant)`；`NotificationEventListener` 注入 `NotificationService` + `ProjectRepository`。Task 3 的 4 个新事件 handler 复用这些签名；Task 4 的 API 方法加在同一 `NotificationService`。

- [ ] **Step 1: Channel 枚举补 WEBHOOK**（Ruling PL-M17-4）

`Channel.kt` 全文改为：

```kotlin
package com.example.compliance.notification.domain

/** 通知渠道（spec M17 §3.2/D4）：WEBHOOK 为 M17 新增（VARCHAR(16) 容纳，无 DDL）。WECHAT/DINGTALK 仍为枚举预留。 */
enum class Channel { IN_APP, EMAIL, WECHAT, DINGTALK, WEBHOOK }
```

- [ ] **Step 2: 重写 NotificationService —— 删除占位契约，落地 notify**

`NotificationService.kt` 全文替换（删除 `: NotificationSender`、`send`、`persist`、`list`）：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.example.compliance.user.infrastructure.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** M17 通知服务（spec R-M17-D4）：事件 → 站内信落库 + EMAIL/Webhook 真实渠道投递。
 *  REQUIRES_NEW：通知写入必须在独立事务 —— 发布方事务内同步 @EventListener 调用本方法时，
 *  通知失败仅回滚通知自身，绝不标记发布方事务 rollback-only（spec §6.4 best-effort）。
 *  收件人解析第二层（R-M17-D2）：userId → email；第一层「事件→userId」在 NotificationEventListener。 */
@Service
class NotificationService(
    private val repository: NotificationRepository,
    private val userRepository: UserRepository,
    private val emailSender: EmailSender,
    private val webhookSender: WebhookSender,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun notify(notificationType: String, title: String, body: String, recipients: List<Long>) {
        val unique = recipients.distinct()
        if (unique.isEmpty()) return
        val occurredAt = Instant.now()
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
            if (emailSender.isAvailable()) {
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
            })
            webhookSender.send(row, unique, occurredAt)
        }
    }
}
```

- [ ] **Step 3: EmailSender 渠道适配器（seam：JavaMailSender 接口）**

创建 `EmailSender.kt`：

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
 *  send 绝不抛出 —— 所有异常置 FAILED（+retry_count+error_message），保护 notify 的 REQUIRES_NEW 事务。 */
@Service
class EmailSender(
    private val mailSenderProvider: ObjectProvider<JavaMailSender>,
    private val repository: NotificationRepository,
) {
    fun isAvailable(): Boolean = mailSenderProvider.getIfAvailable() != null

    fun send(row: Notification) {
        val mailSender = mailSenderProvider.getIfAvailable()
        if (mailSender == null) {
            fail(row, "mail sender not configured")
            return
        }
        try {
            val mime = mailSender.createMimeMessage()
            mime.setFrom(InternetAddress("no-reply@example.com"))
            mime.setRecipients(Message.RecipientType.TO, row.recipient)
            mime.setSubject(row.title, "UTF-8")
            mime.setText(row.content ?: "", "UTF-8")
            mailSender.send(mime)
            row.status = "SENT"
            row.sentAt = Instant.now()
        } catch (e: Exception) {
            fail(row, e.message)
        }
        repository.save(row)
    }

    private fun fail(row: Notification, message: String?) {
        row.status = "FAILED"
        row.retryCount++
        row.errorMessage = message?.take(500)
        repository.save(row)
    }
}
```

- [ ] **Step 4: Webhook seam + 生产客户端 + 适配器**

创建 `WebhookClient.kt`：

```kotlin
package com.example.compliance.notification.application

/** Webhook 渠道 seam（spec R-M17-D5）：自定义接口，生产实现 RestWebhookClient（RestClient POST）。 */
interface WebhookClient {
    /** POST JSON body；成功返回 true。实现不得抛异常 —— 失败返回 false，由 sender 置 FAILED。 */
    fun post(url: String, body: String): Boolean
}
```

创建 `RestWebhookClient.kt`：

```kotlin
package com.example.compliance.notification.application

import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

/** 生产 Webhook 客户端：RestClient POST（spring-boot-starter-web 自动配置 RestClient.Builder）。 */
@Service
class RestWebhookClient(private val builder: RestClient.Builder) : WebhookClient {
    override fun post(url: String, body: String): Boolean =
        runCatching {
            builder.build().post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity()
            true
        }.getOrDefault(false)
}
```

创建 `WebhookSender.kt`：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant

/** Webhook 渠道适配器：标准化 JSON 负载 + 状态流转。url 未配置 → isConfigured()=false，不建 WEBHOOK 行。
 *  send 绝不抛出 —— client 异常或 false 一律置 FAILED，保护 notify 的 REQUIRES_NEW 事务。 */
@Service
class WebhookSender(
    @Value("\${compliance.notification.webhook-url:}") private val webhookUrl: String,
    private val client: WebhookClient,
    private val repository: NotificationRepository,
) {
    private val objectMapper = ObjectMapper()

    fun isConfigured(): Boolean = webhookUrl.isNotBlank()

    fun send(row: Notification, recipientIds: List<Long>, occurredAt: Instant) {
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "type" to row.type,
                "title" to row.title,
                "body" to row.content,
                "recipientIds" to recipientIds,
                "occurredAt" to occurredAt.toString(),
            )
        )
        val ok = try {
            client.post(webhookUrl, payload)
        } catch (e: Exception) {
            false
        }
        if (ok) {
            row.status = "SENT"
            row.sentAt = Instant.now()
        } else {
            row.status = "FAILED"
            row.retryCount++
            row.errorMessage = "webhook post failed"
        }
        repository.save(row)
    }
}
```

- [ ] **Step 5: 删除占位契约**

Delete `NotificationSender.kt` 与 `LogNotificationSender.kt`（旧契约被 notify 取代；grep 证实无其他消费者）。

- [ ] **Step 6: 重写监听器 —— 注入 NotificationService + ProjectRepository，回归收件人修复**

`NotificationEventListener.kt` 全文替换：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.common.event.FindingRegressionEvent
import com.example.compliance.common.event.RemediationWaiverEvent
import com.example.compliance.project.infrastructure.ProjectRepository
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/** 通知事件消费（spec §6.4 best-effort：失败仅日志，不影响发布方主流程）。
 *  收件人解析第一层（R-M17-D2）：事件 → 收件人 userIds（owner/assignee/actor）；
 *  NotificationService.notify 做 userId → email + 渠道 fan-out。
 *  回归收件人修复（spec §3.4）：emptyList() → project.ownerUserId。 */
@Component
class NotificationEventListener(
    private val notificationService: NotificationService,
    private val projectRepository: ProjectRepository,
) {
    private val log = LoggerFactory.getLogger(NotificationEventListener::class.java)

    @EventListener
    fun onRegression(e: FindingRegressionEvent) = safe("regression project=${e.projectId}") {
        ownerRecipient(e.projectId)?.let { owner ->
            notificationService.notify(
                "FINDING_REGRESSION", "finding regressed",
                "回归：${e.findingIds.size} 个 finding 在扫描 ${e.scanTaskId} 复现", listOf(owner),
            )
        }
    }

    @EventListener
    fun onWaiver(e: RemediationWaiverEvent) = safe("waiver project=${e.projectId}") {
        notificationService.notify(
            "REMEDIATION_WAIVER", "finding waived",
            "finding ${e.findingId} 被豁免（${e.reason}）", listOf(e.actorId),
        )
    }

    /** 项目负责人收件人：项目缺失或 owner 为空 → null（不通知）。 */
    private fun ownerRecipient(projectId: Long): Long? =
        projectRepository.findById(projectId).orElse(null)?.ownerUserId

    private fun safe(logCtx: String, block: () -> Unit) {
        runCatching(block).onFailure { log.warn("notification failed: {}", logCtx, it) }
    }
}
```

- [ ] **Step 7: 重写 NotificationServiceTest**

`NotificationServiceTest.kt` 全文替换：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.example.compliance.user.domain.User
import com.example.compliance.user.infrastructure.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationServiceTest {
    private val repository = mockk<NotificationRepository>()
    private val userRepository = mockk<UserRepository>()
    private val emailSender = mockk<EmailSender>()
    private val webhookSender = mockk<WebhookSender>()
    private val service = NotificationService(repository, userRepository, emailSender, webhookSender)

    private fun captured(): MutableList<Notification> {
        val rows = mutableListOf<Notification>()
        every { repository.save(any<Notification>()) } answers { firstArg<Notification>().also { it.id = 1L; rows += it } }
        return rows
    }

    @Test
    fun `notify dedups recipients and creates one IN_APP row per recipient`() {
        val rows = captured()
        every { emailSender.isAvailable() } returns false
        every { webhookSender.isConfigured() } returns false
        service.notify("SCAN_COMPLETED", "t", "b", listOf(1L, 1L, 2L))
        val inApp = rows.filter { it.channel == Channel.IN_APP.name }
        assertEquals(2, inApp.size)
        assertTrue(inApp.all { it.status == "SENT" && it.type == "SCAN_COMPLETED" && it.sentAt != null })
        assertEquals(listOf("1", "2"), inApp.map { it.recipient })
    }

    @Test
    fun `notify with empty recipients persists nothing`() {
        service.notify("SCAN_COMPLETED", "t", "b", emptyList())
        verify(exactly = 0) { repository.save(any<Notification>()) }
    }

    @Test
    fun `email rows only for users with email when sender available`() {
        val rows = captured()
        every { userRepository.findById(1L) } returns Optional.of(User().apply { id = 1L; email = "a@x.com" })
        every { userRepository.findById(2L) } returns Optional.of(User().apply { id = 2L; email = null })
        every { emailSender.isAvailable() } returns true
        every { webhookSender.isConfigured() } returns false
        service.notify("REMEDIATION_ASSIGNED", "t", "b", listOf(1L, 2L))
        val emails = rows.filter { it.channel == Channel.EMAIL.name }
        assertEquals(1, emails.size)
        assertEquals("a@x.com", emails.single().recipient)
        assertEquals("PENDING", emails.single().status)
        verify(exactly = 1) { emailSender.send(emails.single()) }
    }

    @Test
    fun `no email rows when sender unavailable`() {
        val rows = captured()
        every { emailSender.isAvailable() } returns false
        every { webhookSender.isConfigured() } returns false
        service.notify("SCAN_COMPLETED", "t", "b", listOf(1L))
        assertTrue(rows.none { it.channel == Channel.EMAIL.name })
        verify(exactly = 0) { userRepository.findById(any()) }
    }

    @Test
    fun `webhook row only when configured with deduped recipient ids`() {
        val rows = captured()
        every { emailSender.isAvailable() } returns false
        every { webhookSender.isConfigured() } returns true
        service.notify("REPORT_SNAPSHOT_GENERATED", "t", "b", listOf(1L, 1L, 2L))
        val webhook = rows.filter { it.channel == Channel.WEBHOOK.name }
        assertEquals(1, webhook.size)
        assertEquals("webhook", webhook.single().recipient)
        verify { webhookSender.send(webhook.single(), listOf(1L, 2L), any()) }
    }
}
```

- [ ] **Step 8: 重写 NotificationEventListenerTest**

`NotificationEventListenerTest.kt` 全文替换：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.common.event.FindingRegressionEvent
import com.example.compliance.common.event.RemediationWaiverEvent
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
    fun `regression resolves project owner as recipient`() {
        every { projectRepository.findById(1L) } returns Optional.of(project(7L))
        listener.onRegression(FindingRegressionEvent(1L, 2L, listOf(3L)))
        verify { service.notify("FINDING_REGRESSION", "finding regressed", "回归：1 个 finding 在扫描 2 复现", listOf(7L)) }
    }

    @Test
    fun `regression with no owner notifies nothing`() {
        every { projectRepository.findById(1L) } returns Optional.of(project(null))
        listener.onRegression(FindingRegressionEvent(1L, 2L, listOf(3L)))
        verify(exactly = 0) { service.notify(any(), any(), any(), any()) }
    }

    @Test
    fun `regression with missing project notifies nothing`() {
        every { projectRepository.findById(99L) } returns Optional.empty()
        listener.onRegression(FindingRegressionEvent(99L, 2L, listOf(3L)))
        verify(exactly = 0) { service.notify(any(), any(), any(), any()) }
    }

    @Test
    fun `waiver notifies actor`() {
        listener.onWaiver(RemediationWaiverEvent(1L, 2L, 3L, "r"))
        verify { service.notify("REMEDIATION_WAIVER", "finding waived", "finding 2 被豁免（r）", listOf(3L)) }
    }

    @Test
    fun `service failure does not propagate`() {
        every { projectRepository.findById(1L) } returns Optional.of(project(7L))
        every { service.notify(any(), any(), any(), any()) } throws RuntimeException("boom")
        listener.onRegression(FindingRegressionEvent(1L, 2L, listOf(3L)))   // 不抛 —— runCatching 兜底
        listener.onWaiver(RemediationWaiverEvent(1L, 2L, 3L, "r"))
    }
}
```

- [ ] **Step 9: 新建 EmailSenderTest**

创建 `EmailSenderTest.kt`：

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
import kotlin.test.assertTrue

class EmailSenderTest {
    private val provider = mockk<ObjectProvider<JavaMailSender>>()
    private val repository = mockk<NotificationRepository>()
    private val sender = EmailSender(provider, repository)

    private fun row() = Notification().apply {
        id = 1L; channel = Channel.EMAIL.name; recipient = "a@x.com"; type = "SCAN_COMPLETED"
        title = "scan completed"; content = "body"; status = "PENDING"
    }

    @Test
    fun `send builds mime and marks sent`() {
        val stub = StubJavaMailSender()
        every { provider.getIfAvailable() } returns stub
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        sender.send(row)
        assertEquals("SENT", row.status)
        assertNotNull(row.sentAt)
        assertEquals(1, stub.sent.size)
        assertEquals("a@x.com", stub.sent.single().getRecipients(jakarta.mail.Message.RecipientType.TO)?.first()?.toString())
        assertEquals("scan completed", stub.sent.single().subject)
        verify { repository.save(row) }
    }

    @Test
    fun `send failure marks failed with retry and error`() {
        val stub = StubJavaMailSender()
        stub.failWith = RuntimeException("smtp down")
        every { provider.getIfAvailable() } returns stub
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        sender.send(row)
        assertEquals("FAILED", row.status)
        assertEquals(1, row.retryCount)
        assertTrue(!row.errorMessage.isNullOrBlank())
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

- [ ] **Step 10: 新建 WebhookSenderTest**

创建 `WebhookSenderTest.kt`：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookSenderTest {
    private val client = mockk<WebhookClient>()
    private val repository = mockk<NotificationRepository>()
    private val sender = WebhookSender("http://hook.test/x", client, repository)

    private fun row() = Notification().apply {
        id = 1L; channel = Channel.WEBHOOK.name; recipient = "webhook"; type = "SCAN_COMPLETED"
        title = "scan completed"; content = "body"; status = "PENDING"
    }

    @Test
    fun `send posts json and marks sent`() {
        every { client.post("http://hook.test/x", any()) } returns true
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        sender.send(row, listOf(1L, 2L), Instant.parse("2026-09-06T00:00:00Z"))
        assertEquals("SENT", row.status)
        val posted = slot<String>()
        verify { client.post("http://hook.test/x", capture(posted)) }
        assertTrue(posted.captured.contains("\"type\":\"SCAN_COMPLETED\""))
        assertTrue(posted.captured.contains("\"recipientIds\":[1,2]"))
        assertTrue(posted.captured.contains("2026-09-06T00:00:00Z"))
    }

    @Test
    fun `send failure marks failed and increments retry`() {
        every { client.post(any(), any()) } returns false
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        sender.send(row, listOf(1L), Instant.now())
        assertEquals("FAILED", row.status)
        assertEquals(1, row.retryCount)
        assertEquals("webhook post failed", row.errorMessage)
    }

    @Test
    fun `send swallows client exception as failed`() {
        every { client.post(any(), any()) } throws RuntimeException("http down")
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        sender.send(row, listOf(1L), Instant.now())
        assertEquals("FAILED", row.status)
    }

    @Test
    fun `isConfigured reflects url`() {
        assertTrue(sender.isConfigured())
        assertFalse(WebhookSender("", client, repository).isConfigured())
    }
}
```

- [ ] **Step 11: 删除 LogNotificationSenderTest**

Delete `LogNotificationSenderTest.kt`（占位契约已删）。

- [ ] **Step 12: 运行 module-notification 测试**

Run: `./gradlew :module-notification:test`
Expected: 全绿（notify 五测 + 监听器五测 + EmailSender 四测 + WebhookSender 四测）；`NotificationSender`/`LogNotificationSender` 无引用（删除干净）。

- [ ] **Step 13: 全量编译（其他模块不受影响）**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL（grep 已证实 NotificationSender 仅 module-notification 内部使用）。

- [ ] **Step 14: Commit**

```bash
git add -A module-notification
git commit -m "feat(notification): M17 delivery core — notify fan-out + email/webhook adapters + listener rewiring, fix regression recipients (m17)"
```

---

### Task 3: 事件接线（4 新事件 + 发布方 publish + 监听器扩展 + 既有测试更新）

**Files:**
- Create: `module-common/src/main/kotlin/com/example/compliance/common/event/ScanCompletedEvent.kt`
- Create: `module-common/src/main/kotlin/com/example/compliance/common/event/ReportSnapshotGeneratedEvent.kt`
- Create: `module-common/src/main/kotlin/com/example/compliance/common/event/RemediationAssignedEvent.kt`
- Create: `module-common/src/main/kotlin/com/example/compliance/common/event/RemediationCompletedEvent.kt`
- Modify: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanOrchestrator.kt`（终态处 publish）
- Modify: `module-report/src/main/kotlin/com/example/compliance/report/application/ReportGenerationService.kt`（快照 persist 后 publish）
- Modify: `module-remediation/src/main/kotlin/com/example/compliance/remediation/application/RemediationService.kt`（assign/markFixed publish + 既有 waiver publish 硬化）
- Modify: `module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationEventListener.kt`（新增 4 handler）
- Modify: `module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationEventListenerTest.kt`（新增 4 handler 测试）
- Modify: `module-report/src/test/kotlin/com/example/compliance/report/application/ReportGenerationServiceTest.kt`
- Modify: `module-remediation/src/test/kotlin/com/example/compliance/remediation/application/RemediationServiceTest.kt`
- Modify: `app-server/src/test/kotlin/com/example/compliance/notification/M10NotificationEventIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `NotificationService.notify(type, title, body, recipients)` 与 `NotificationEventListener`（构造已注入 service + projectRepository）。
- Produces: 4 个 common 事件 data class（见各 Step 代码）；发布方在真实终态/成功后 publish；监听器 6 个 handler 全集（含回归 owner 解析、报告 null project 跳过）；`RemediationService` 增 2 个 publish + waiver publish runCatching 硬化；`ReportGenerationService` 构造增 `eventPublisher`；`ScanOrchestrator` 构造增 `eventPublisher`。Task 5 集成测试依赖这些事件与行为。

- [ ] **Step 1: 4 个 common 事件类**

创建 `module-common/src/main/kotlin/com/example/compliance/common/event/ScanCompletedEvent.kt`：

```kotlin
package com.example.compliance.common.event

/** 扫描任务达终态事件（spec M17 §3.4）：module-scan 任务终态（SUCCESS/FAILED）时发布。
 *  接线点在 ScanOrchestrator（Ruling PL-M17-1：终态流转实际发生在编排器，非 ScanTaskService）。 */
data class ScanCompletedEvent(
    val scanTaskId: Long,
    val projectId: Long,
    val status: String,
)
```

创建 `module-common/src/main/kotlin/com/example/compliance/common/event/ReportSnapshotGeneratedEvent.kt`：

```kotlin
package com.example.compliance.common.event

/** 报告快照生成事件（spec M17 §3.4）：module-report 快照 persist 后发布。
 *  projectId 可空（Ruling PL-M17-2）：SCAN_SUMMARY 快照无单一项目，null 时监听器不通知。 */
data class ReportSnapshotGeneratedEvent(
    val snapshotId: Long,
    val projectId: Long?,
    val snapshotType: String,
)
```

创建 `module-common/src/main/kotlin/com/example/compliance/common/event/RemediationAssignedEvent.kt`：

```kotlin
package com.example.compliance.common.event

/** 整改指派事件（spec M17 §3.4）：module-remediation assign 命令成功且首次指派给具体受让人时发布（Ruling PL-M17-6）。 */
data class RemediationAssignedEvent(
    val findingId: Long,
    val projectId: Long,
    val assigneeId: Long,
)
```

创建 `module-common/src/main/kotlin/com/example/compliance/common/event/RemediationCompletedEvent.kt`：

```kotlin
package com.example.compliance.common.event

/** 整改完成事件（spec M17 §3.4）：module-remediation fixed 命令成功时发布。assigneeId 可空（未派单）。 */
data class RemediationCompletedEvent(
    val findingId: Long,
    val projectId: Long,
    val actorId: Long,
    val assigneeId: Long?,
)
```

- [ ] **Step 2: ScanOrchestrator 终态 publish**

`ScanOrchestrator.kt` 修改：
1. 顶部 import 增两行：
```kotlin
import com.example.compliance.common.event.ScanCompletedEvent
import org.springframework.context.ApplicationEventPublisher
```
2. 构造参数 `private val credentialCrypto: CredentialCrypto,` 之后（`@Value` 之前）增：
```kotlin
    private val eventPublisher: ApplicationEventPublisher,
```
3. SUCCESS 终态（现 `task.finishedAt = Instant.now()` + `scanTaskRepository.save(task)` 之后、`log(scanTaskId, "SCAN", "INFO", ...)` 之前）插入：
```kotlin
            // M17：扫描终态事件（best-effort —— 监听器失败不得把成功扫描打成 FAILED，Ruling PL-M17-8）
            runCatching { eventPublisher.publishEvent(ScanCompletedEvent(scanTaskId, task.projectId, task.status.name)) }
```
4. FAILED 终态（catch 内 `scanTaskRepository.save(task)` 之后、`task.requestId?.takeIf {...}` 之前）插入：
```kotlin
            // M17：扫描终态事件（best-effort）
            runCatching { eventPublisher.publishEvent(ScanCompletedEvent(scanTaskId, task.projectId, task.status.name)) }
```

- [ ] **Step 3: ReportGenerationService 快照 persist 后 publish**

`ReportGenerationService.kt` 修改：
1. import 增：
```kotlin
import com.example.compliance.common.event.ReportSnapshotGeneratedEvent
import org.springframework.context.ApplicationEventPublisher
```
2. 构造参数增 `private val eventPublisher: ApplicationEventPublisher`（放在 `snapshotRepository` 之后）。
3. `generate` 的 return 改为（保存后发布，再返回）：
```kotlin
        val snapshot = snapshotRepository.save(ReportSnapshot().apply {
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
        // M17：快照就绪事件（best-effort —— 监听器失败不得回滚快照生成，Ruling PL-M17-8）
        runCatching { eventPublisher.publishEvent(ReportSnapshotGeneratedEvent(snapshot.id!!, projectId, type)) }
        return snapshot
```

- [ ] **Step 4: RemediationService 增 publish + waiver 硬化**

`RemediationService.kt` 修改：
1. import 增：
```kotlin
import com.example.compliance.common.event.RemediationAssignedEvent
import com.example.compliance.common.event.RemediationCompletedEvent
```
2. `assign` 末尾（`return FindingRemediationView(...)` 处）改为：
```kotlin
        val view = FindingRemediationView(finding.copy(status = newStatus), taskRepository.save(task).toView())
        // M17：首次指派且指定受让人 → 指派事件（Ruling PL-M17-6；best-effort，Ruling PL-M17-8）
        if (existing == null && assigneeUserId != null) {
            runCatching { eventPublisher.publishEvent(RemediationAssignedEvent(finding.id, finding.projectId, assigneeUserId)) }
        }
        return view
```
3. `markFixed` 末尾（`return mirrorTransition(...)` 处）改为（复用已在方法内算好的 `assignee`）：
```kotlin
        lifecyclePort.addEvidence(findingId, evidenceType, evidenceRef, actorId)
        val view = mirrorTransition(findingId, FindingStatus.FIXED, "fixed", actorId)
        // M17：整改完成事件（best-effort）—— actor 必收，assignee 若存在则并列
        runCatching {
            eventPublisher.publishEvent(RemediationCompletedEvent(findingId, finding.projectId, actorId, assignee))
        }
        return view
```
4. 既有 WAIVED publish 硬化（`status` 方法内，M10 原行为 `eventPublisher.publishEvent(...)` 包 runCatching）：
```kotlin
        if (to == FindingStatus.WAIVED) {
            // M17 硬化：publish 失败也不得回滚 WAIVED 终态（spec red line，Ruling PL-M17-8）
            runCatching { eventPublisher.publishEvent(RemediationWaiverEvent(finding.projectId, findingId, actorId, reason)) }
        }
```

- [ ] **Step 5: 监听器新增 4 个 handler**

`NotificationEventListener.kt`（Task 2 已重写）追加 import 与 4 个 handler（在 `onWaiver` 之后）：

import 增：
```kotlin
import com.example.compliance.common.event.RemediationAssignedEvent
import com.example.compliance.common.event.RemediationCompletedEvent
import com.example.compliance.common.event.ReportSnapshotGeneratedEvent
import com.example.compliance.common.event.ScanCompletedEvent
```

handler：
```kotlin
    @EventListener
    fun onScanCompleted(e: ScanCompletedEvent) = safe("scan project=${e.projectId}") {
        ownerRecipient(e.projectId)?.let { owner ->
            notificationService.notify("SCAN_COMPLETED", "scan completed", "扫描 ${e.scanTaskId} 完成：${e.status}", listOf(owner))
        }
    }

    @EventListener
    fun onReportSnapshotGenerated(e: ReportSnapshotGeneratedEvent) = safe("report snapshot project=${e.projectId}") {
        e.projectId?.let { projectId -> ownerRecipient(projectId) }?.let { owner ->
            notificationService.notify(
                "REPORT_SNAPSHOT_GENERATED", "report snapshot generated",
                "快照 ${e.snapshotId} 已生成（${e.snapshotType}）", listOf(owner),
            )
        }
    }

    @EventListener
    fun onRemediationAssigned(e: RemediationAssignedEvent) = safe("remediation assigned finding=${e.findingId}") {
        notificationService.notify("REMEDIATION_ASSIGNED", "remediation assigned", "finding ${e.findingId} 已指派给你", listOf(e.assigneeId))
    }

    @EventListener
    fun onRemediationCompleted(e: RemediationCompletedEvent) = safe("remediation completed finding=${e.findingId}") {
        notificationService.notify(
            "REMEDIATION_COMPLETED", "remediation completed",
            "finding ${e.findingId} 已标记完成", listOfNotNull(e.actorId, e.assigneeId).distinct(),
        )
    }
```

- [ ] **Step 6: 监听器测试补 4 个新 handler 用例**

`NotificationEventListenerTest.kt` 追加 import 与测试：

```kotlin
import com.example.compliance.common.event.RemediationAssignedEvent
import com.example.compliance.common.event.RemediationCompletedEvent
import com.example.compliance.common.event.ReportSnapshotGeneratedEvent
import com.example.compliance.common.event.ScanCompletedEvent
```

```kotlin
    @Test
    fun `scan completed resolves owner`() {
        every { projectRepository.findById(1L) } returns Optional.of(project(7L))
        listener.onScanCompleted(ScanCompletedEvent(1L, 1L, "SUCCESS"))
        verify { service.notify("SCAN_COMPLETED", "scan completed", "扫描 1 完成：SUCCESS", listOf(7L)) }
    }

    @Test
    fun `scan completed with no owner notifies nothing`() {
        every { projectRepository.findById(1L) } returns Optional.of(project(null))
        listener.onScanCompleted(ScanCompletedEvent(1L, 1L, "FAILED"))
        verify(exactly = 0) { service.notify(any(), any(), any(), any()) }
    }

    @Test
    fun `report snapshot generated resolves owner`() {
        every { projectRepository.findById(1L) } returns Optional.of(project(7L))
        listener.onReportSnapshotGenerated(ReportSnapshotGeneratedEvent(5L, 1L, "COMPLIANCE"))
        verify { service.notify("REPORT_SNAPSHOT_GENERATED", "report snapshot generated", "快照 5 已生成（COMPLIANCE）", listOf(7L)) }
    }

    @Test
    fun `report snapshot with null project notifies nothing`() {
        listener.onReportSnapshotGenerated(ReportSnapshotGeneratedEvent(5L, null, "SCAN_SUMMARY"))
        verify(exactly = 0) { service.notify(any(), any(), any(), any()) }
    }

    @Test
    fun `remediation assigned notifies assignee`() {
        listener.onRemediationAssigned(RemediationAssignedEvent(3L, 9L, 4L))
        verify { service.notify("REMEDIATION_ASSIGNED", "remediation assigned", "finding 3 已指派给你", listOf(4L)) }
    }

    @Test
    fun `remediation completed notifies actor and assignee deduped`() {
        listener.onRemediationCompleted(RemediationCompletedEvent(3L, 9L, 4L, 4L))
        verify { service.notify("REMEDIATION_COMPLETED", "remediation completed", "finding 3 已标记完成", listOf(4L)) }
    }

    @Test
    fun `remediation completed notifies actor and assignee`() {
        listener.onRemediationCompleted(RemediationCompletedEvent(3L, 9L, 4L, 5L))
        verify { service.notify("REMEDIATION_COMPLETED", "remediation completed", "finding 3 已标记完成", listOf(4L, 5L)) }
    }
```

- [ ] **Step 7: ReportGenerationServiceTest 适配新构造 + 验证发布**

`ReportGenerationServiceTest.kt` 修改：
1. import 增 `com.example.compliance.common.event.ReportSnapshotGeneratedEvent`、`org.springframework.context.ApplicationEventPublisher`。
2. 字段与构造改为：
```kotlin
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val service = ReportGenerationService(reportService, templateRepository, versionRepository, snapshotRepository, eventPublisher)
```
3. 三个成功 generate 测试（`scan summary...`、`compliance generation...`、`trend generation...`）的 `snapshotRepository.save` stub 从 `answers { firstArg() }` 改为 `answers { firstArg<ReportSnapshot>().also { it.id = 5L } }`（否则 `snapshot.id!!` 空指针）。
4. `scan summary generation...` 测试末尾追加发布验证：
```kotlin
        verify {
            eventPublisher.publishEvent(match<Any> { it is ReportSnapshotGeneratedEvent && it.snapshotId == 5L && it.projectId == null && it.snapshotType == "SCAN_SUMMARY" })
        }
```
（`compliance`/`trend` 测试可选追加，projectId=88L 断言同理。）

- [ ] **Step 8: RemediationServiceTest 补新 publish 验证**

`RemediationServiceTest.kt` 修改：
1. import 增：
```kotlin
import com.example.compliance.common.event.RemediationAssignedEvent
import com.example.compliance.common.event.RemediationCompletedEvent
```
2. `assign creates task and mirrors assigned status` 末尾追加：
```kotlin
        verify { eventPublisher.publishEvent(match<Any> { it is RemediationAssignedEvent && it.findingId == 7L && it.projectId == 9L && it.assigneeId == 3L }) }
```
3. `fixed requires evidence and transitions from fixing`（task 无 assignee）末尾追加：
```kotlin
        verify { eventPublisher.publishEvent(match<Any> { it is RemediationCompletedEvent && it.findingId == 7L && it.actorId == 9L && it.assigneeId == null }) }
```
4. `markFixed accepts the assignee`（assignee=9L）末尾追加：
```kotlin
        verify { eventPublisher.publishEvent(match<Any> { it is RemediationCompletedEvent && it.assigneeId == 9L }) }
```

- [ ] **Step 9: 更新 M10 集成测试（通知语义随 M17 变化）**

`app-server/src/test/kotlin/com/example/compliance/notification/M10NotificationEventIntegrationTest.kt` 修改：
1. import 增 `com.example.compliance.project.domain.Project`、`com.example.compliance.project.infrastructure.ProjectRepository`，`@Autowired` 增 `projectRepository`。
2. waiver 测试断言 `it.type == "EVENT"` 改为 `it.type == "REMEDIATION_WAIVER"`。
3. 回归测试改为「seed 项目带 owner → owner 收回归通知」（emptyList bug 修复实证，且按 recipient+title 断言确定无歧义）：
```kotlin
    @Test
    fun `regression event notifies the project owner`() {
        val project = projectRepository.save(Project().apply {
            code = "M10REGRESS"; name = "m10 regression"; ownerUserId = 7L
        })
        publisher.publishEvent(FindingRegressionEvent(project.id!!, 8801L, listOf(2L, 3L)))
        val rows = notificationRepository.findByRecipient("7")
        assertTrue(rows.any { it.type == "FINDING_REGRESSION" && it.status == "SENT" && it.title == "finding regressed" })
    }
```
（类注释中 `NotificationSender` 措辞一并更新为 `NotificationService`。回归测试保留原断言语义——项目缺失则无通知——不需要；上面新版按 owner 验证修复。）

- [ ] **Step 10: 运行受影响模块测试**

Run: `./gradlew :module-common:test :module-notification:test :module-scan:test :module-report:test :module-remediation:test :app-server:compileTestKotlin`
Expected: 全绿。注意：`:app-server:test` 全量留到 Task 5 门禁跑（M10 集成测试已在本步改好，其 Testcontainers 执行归 Task 5）。

- [ ] **Step 11: Commit**

```bash
git add -A module-common module-scan module-report module-remediation module-notification app-server/src/test/kotlin/com/example/compliance/notification
git commit -m "feat(notification): M17 event wiring — scan/report/remediation events, listener expansion, best-effort publish guards (m17)"
```

---

### Task 4: 站内信 API（NotificationController + 读态查询 + 切片测试）

**Files:**
- Modify: `module-notification/src/main/kotlin/com/example/compliance/notification/infrastructure/NotificationRepository.kt`
- Modify: `module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationService.kt`
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/api/NotificationController.kt`
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/api/NotificationDtos.kt`
- Create: `module-notification/src/test/kotlin/com/example/compliance/notification/NotificationTestConfig.kt`
- Create: `module-notification/src/test/kotlin/com/example/compliance/notification/api/NotificationControllerTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `NotificationService`（构造已含 repository/userRepository/emailSender/webhookSender）。
- Produces: `NotificationRepository` 增 `findByRecipientAndChannel(recipient, channel, pageable)`、`findByRecipientAndChannelAndReadAtIsNull(...)`、`countByRecipientAndChannelAndReadAtIsNull(recipient, channel)`、`findByIdAndRecipientAndChannel(id, recipient, channel)`、`markReadAll(recipient, channel, now): Int`（@Modifying @Query）；`NotificationService.listMy(userId, page, size, unreadOnly): Page<Notification>`、`unreadCount(userId): Long`、`markRead(id, userId)`、`markReadAll(userId): Int`；`NotificationView`/`UnreadCount` DTO；`NotificationController`（4 端点，@PreAuthorize("isAuthenticated()")）。Task 5 集成测试直接消费这些端点。

- [ ] **Step 1: Repository 读态查询**

`NotificationRepository.kt` 全文替换：

```kotlin
package com.example.compliance.notification.infrastructure

import com.example.compliance.notification.domain.Notification
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface NotificationRepository : JpaRepository<Notification, Long> {
    fun findByStatusAndChannel(status: String, channel: String): List<Notification>
    fun findByRecipient(recipient: String): List<Notification>

    // M17 站内信中心（spec §3.3/§3.5）：按收件人 + IN_APP 渠道，id DESC 分页（service 传 Sort）
    fun findByRecipientAndChannel(recipient: String, channel: String, pageable: Pageable): Page<Notification>
    fun findByRecipientAndChannelAndReadAtIsNull(recipient: String, channel: String, pageable: Pageable): Page<Notification>
    fun countByRecipientAndChannelAndReadAtIsNull(recipient: String, channel: String): Long
    fun findByIdAndRecipientAndChannel(id: Long, recipient: String, channel: String): Notification?

    /** 批量已读（返回受影响行数，service 作为 {count} 返回）。 */
    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.recipient = :recipient AND n.channel = :channel AND n.readAt IS NULL")
    fun markReadAll(@Param("recipient") recipient: String, @Param("channel") channel: String, @Param("now") now: Instant): Int
}
```

- [ ] **Step 2: NotificationService 增 API 方法**

`NotificationService.kt` 追加 import 与方法（在 `notify` 之后）：

import 增：
```kotlin
import com.example.compliance.common.exception.BusinessException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
```

方法：
```kotlin
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

- [ ] **Step 3: DTO**

创建 `api/NotificationDtos.kt`：

```kotlin
package com.example.compliance.notification.api

import com.example.compliance.notification.domain.Notification
import java.time.Instant

/** 站内信视图（spec §3.5）。 */
data class NotificationView(
    val id: Long,
    val type: String,
    val title: String,
    val content: String?,
    val channel: String,
    val status: String,
    val readAt: Instant?,
    val createdAt: Instant?,
) {
    companion object {
        fun from(e: Notification) = NotificationView(
            id = e.id!!, type = e.type, title = e.title, content = e.content,
            channel = e.channel, status = e.status, readAt = e.readAt, createdAt = e.createdAt,
        )
    }
}

/** 未读计数 / 标记数（{count} 形状）。 */
data class UnreadCount(val count: Long)
```

- [ ] **Step 4: 控制器**

创建 `api/NotificationController.kt`：

```kotlin
package com.example.compliance.notification.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.common.api.PageResponse
import com.example.compliance.common.auth.AuthPrincipal
import com.example.compliance.notification.application.NotificationService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

/** 站内信中心（spec §3.5）：当前用户自助（无 admin 全量视图）。全部按 AuthPrincipal.userId 过滤 IN_APP 行。
 *  方法级 @PreAuthorize 显式声明意图；路由落 SecurityConfig `anyRequest().authenticated()`（无 SecurityConfig 改动）。 */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(private val service: NotificationService) {

    @PreAuthorize("isAuthenticated()")
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "false") unreadOnly: Boolean,
        authentication: Authentication?,
    ): ApiResponse<PageResponse<NotificationView>> {
        val result = service.listMy(userId(authentication), page, size, unreadOnly)
        return ApiResponse.ok(
            PageResponse(
                items = result.content.map { NotificationView.from(it) },
                page = result.number,
                size = result.size,
                total = result.totalElements,
            )
        )
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/unread-count")
    fun unreadCount(authentication: Authentication?): ApiResponse<UnreadCount> =
        ApiResponse.ok(UnreadCount(service.unreadCount(userId(authentication))))

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{id}/read")
    fun read(@PathVariable id: Long, authentication: Authentication?): ApiResponse<Unit> {
        service.markRead(id, userId(authentication))
        return ApiResponse.ok()
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/read-all")
    fun readAll(authentication: Authentication?): ApiResponse<UnreadCount> =
        ApiResponse.ok(UnreadCount(service.markReadAll(userId(authentication)).toLong()))

    /** 真实用户 id（镜像 ReportSnapshotController/RemediationController）：AuthPrincipal 解析，非 AuthPrincipal 回落 1L。 */
    private fun userId(authentication: Authentication?): Long =
        (authentication?.principal as? AuthPrincipal)?.userId ?: 1L
}
```

- [ ] **Step 5: 切片上下文标记**

创建 `NotificationTestConfig.kt`（`com.example.compliance.notification` 包，镜像 AdminTestConfig）：

```kotlin
package com.example.compliance.notification

import org.springframework.boot.autoconfigure.SpringBootApplication

/** @WebMvcTest 上下文标记：module-notification 无自身 @SpringBootApplication（app-server 启动类不在本模块
 *  测试类路径上），切片需以此为配置入口 + 组件扫描根（拾取 com.example.compliance.notification 下的 controller）。 */
@SpringBootApplication
class NotificationTestConfig
```

- [ ] **Step 6: 控制器切片测试**

创建 `api/NotificationControllerTest.kt`：

```kotlin
package com.example.compliance.notification.api

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.common.exception.GlobalExceptionHandler
import com.example.compliance.notification.application.NotificationService
import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/** M17 站内信端点切片（镜像 M16：GlobalExceptionHandler 显式 @Import 才能断言 400 {code:400}）。 */
@WebMvcTest(NotificationController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class NotificationControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var service: NotificationService

    @TestConfiguration
    class NotifServiceConfig {
        @Bean
        fun notificationService(): NotificationService = mockk()
    }

    private fun row(id: Long) = Notification().apply {
        this.id = id; channel = Channel.IN_APP.name; recipient = "1"; type = "SCAN_COMPLETED"
        title = "scan completed"; content = "body"; status = "SENT"; createdAt = Instant.now()
    }

    @Test
    fun `list returns paged views for current user`() {
        val page = PageImpl(listOf(row(1L)), PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id")), 1L)
        every { service.listMy(1L, 0, 20, false) } returns page
        mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(1))
            .andExpect(jsonPath("$.data.items[0].status").value("SENT"))
    }

    @Test
    fun `list unreadOnly passes flag`() {
        every { service.listMy(1L, 0, 20, true) } returns PageImpl(emptyList(), PageRequest.of(0, 20), 0L)
        mockMvc.perform(get("/api/v1/notifications?unreadOnly=true"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(0))
    }

    @Test
    fun `unread-count returns count`() {
        every { service.unreadCount(1L) } returns 3L
        mockMvc.perform(get("/api/v1/notifications/unread-count"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.count").value(3))
    }

    @Test
    fun `read marks notification read and is idempotent`() {
        mockMvc.perform(post("/api/v1/notifications/7/read"))
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/notifications/7/read"))
            .andExpect(status().isOk)
        verify(exactly = 2) { service.markRead(7L, 1L) }
    }

    @Test
    fun `read-all returns count`() {
        every { service.markReadAll(1L) } returns 2
        mockMvc.perform(post("/api/v1/notifications/read-all"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.count").value(2))
    }

    @Test
    fun `read foreign or missing notification is 404 via global handler`() {
        every { service.markRead(9L, 1L) } throws BusinessException(404, "notification not found: 9")
        mockMvc.perform(post("/api/v1/notifications/9/read"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(404))
    }

    @Test
    fun `page below zero is 400 via global handler`() {
        every { service.listMy(1L, -1, 20, false) } throws BusinessException(400, "page must be non-negative")
        mockMvc.perform(get("/api/v1/notifications?page=-1"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
    }
}
```

- [ ] **Step 7: 运行 module-notification 测试**

Run: `./gradlew :module-notification:test`
Expected: 全绿（Task 2 既有 + 本任务切片六测）。

- [ ] **Step 8: Commit**

```bash
git add -A module-notification
git commit -m "feat(notification): M17 in-app notification center API — list/unread-count/read/read-all (m17)"
```

---

### Task 5: 集成测试 + 全量构建门

**Files:**
- Modify: `app-server/build.gradle.kts`（testImplementation 补 spring-boot-starter-mail，Ruling PL-M17-7）
- Create: `app-server/src/test/kotlin/com/example/compliance/notification/M17NotificationCenterIntegrationTest.kt`
- Create: `app-server/src/test/kotlin/com/example/compliance/notification/M17NotificationDeliveryIntegrationTest.kt`
- Create: `app-server/src/test/kotlin/com/example/compliance/notification/M17NotificationEventIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 2 的 notify/EmailSender/WebhookSender/监听器；Task 3 的 4 个事件与发布行为（含回归 owner 解析）；Task 4 的 `/api/v1/notifications` 端点与 `NotificationRepository` 读态查询。
- Produces: 三个集成测试类（数据前缀：项目 `M17DLV1/2`、`M17EVT1..4`，用户 `m17dlv-*`/`m17evt-*`，标题 `NTF-M17-*`）；`./gradlew build` 全绿。

- [ ] **Step 1: app-server 测试依赖补 mail**

`app-server/build.gradle.kts` 的 `testImplementation(libs.spring.security.test)` 之后追加：

```kotlin
    testImplementation(libs.spring.boot.starter.mail)   // M17 集成测试 StubJavaMailSender 直接引用 JavaMailSender/jakarta.mail（Ruling PL-M17-7）
```

- [ ] **Step 2: 站内信中心集成测试**

创建 `M17NotificationCenterIntegrationTest.kt`：

```kotlin
package com.example.compliance.notification

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/** M17 站内信中心 e2e：真实 SecurityConfig + @PreAuthorize + 当前用户过滤。
 *  数据：recipient "1"（m17-user String principal → userId 1L），标题 NTF-M17-*（module 标记，全局唯一）。 */
@AutoConfigureMockMvc
class M17NotificationCenterIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var repository: NotificationRepository

    private fun seed(recipient: String, title: String, readAt: Instant? = null) =
        repository.save(Notification().apply {
            channel = Channel.IN_APP.name; this.recipient = recipient; type = "SCAN_COMPLETED"
            this.title = title; content = "body"; status = "SENT"; sentAt = Instant.now(); this.readAt = readAt
        })

    @Test
    fun `current user lists own notifications and counts unread`() {
        seed("1", "NTF-M17-LIST-READ", Instant.now())
        seed("1", "NTF-M17-LIST-UNREAD")
        seed("2", "NTF-M17-LIST-OTHER")   // 他人行不可见

        mockMvc.perform(get("/api/v1/notifications").with(user("m17-user")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(2))
            .andExpect(jsonPath("$.data.items[0].title").value("NTF-M17-LIST-UNREAD"))   // id DESC 最新在前

        mockMvc.perform(get("/api/v1/notifications/unread-count").with(user("m17-user")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.count").value(1))
    }

    @Test
    fun `read marks own notification read and foreign read is 404`() {
        val mine = seed("1", "NTF-M17-READ-MINE")
        val theirs = seed("2", "NTF-M17-READ-THEIRS")

        mockMvc.perform(post("/api/v1/notifications/${theirs.id}/read").with(user("m17-user")))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(404))

        mockMvc.perform(post("/api/v1/notifications/${mine.id}/read").with(user("m17-user")))
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/notifications/${mine.id}/read").with(user("m17-user")))   // 幂等
            .andExpect(status().isOk)

        mockMvc.perform(get("/api/v1/notifications/unread-count").with(user("m17-user")))
            .andExpect(jsonPath("$.data.count").value(0))
    }

    @Test
    fun `read-all marks all own unread read`() {
        seed("1", "NTF-M17-RA-A")
        seed("1", "NTF-M17-RA-B")
        mockMvc.perform(post("/api/v1/notifications/read-all").with(user("m17-user")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.count").value(2))
        mockMvc.perform(get("/api/v1/notifications/unread-count").with(user("m17-user")))
            .andExpect(jsonPath("$.data.count").value(0))
    }

    @Test
    fun `unauthenticated access is 401`() {
        mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isUnauthorized)
    }
}
```

- [ ] **Step 3: 投递集成测试（stub 渠道 + webhook-url 配置）**

创建 `M17NotificationDeliveryIntegrationTest.kt`：

```kotlin
package com.example.compliance.notification

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.common.event.RemediationAssignedEvent
import com.example.compliance.common.event.ScanCompletedEvent
import com.example.compliance.notification.application.WebhookClient
import com.example.compliance.notification.domain.Channel
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
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M17 投递 e2e：真实监听器/notify + stub 渠道（spec R-M17-D5，不引入 Greenmail/MockWebServer）。
 *  @TestPropertySource 开启 webhook-url → WEBHOOK 行创建；stub bean @Primary 覆盖 WebhookClient（RestWebhookClient 同型歧义）。
 *  数据前缀：项目 M17DLV1/2、用户 m17dlv-*（跨类全局唯一，共享容器）。 */
@TestPropertySource(properties = ["compliance.notification.webhook-url=http://webhook.test/hook"])
class M17NotificationDeliveryIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var publisher: ApplicationEventPublisher
    @Autowired lateinit var repository: NotificationRepository
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

    @Test
    fun `scan completed notifies owner by email and webhook with statuses`() {
        val owner = ownerUser("m17dlv-owner", "m17dlv-owner@example.com")
        val projectId = projectRepository.save(Project().apply { code = "M17DLV1"; name = "m17"; ownerUserId = owner }).id!!

        publisher.publishEvent(ScanCompletedEvent(901L, projectId, "SUCCESS"))

        // IN_APP：落库即 SENT
        val inApp = repository.findByRecipient(owner.toString()).filter { it.type == "SCAN_COMPLETED" }
        assertTrue(inApp.any { it.channel == Channel.IN_APP.name && it.status == "SENT" })

        // EMAIL：经 StubJavaMailSender 捕获 MimeMessage，行 SENT
        val emailRow = repository.findByRecipient("m17dlv-owner@example.com").filter { it.type == "SCAN_COMPLETED" }
        assertEquals(1, emailRow.size)
        assertEquals("SENT", emailRow.single().status)
        assertEquals(1, mailStub.sent.size)
        assertEquals(
            "m17dlv-owner@example.com",
            mailStub.sent.single().getRecipients(jakarta.mail.Message.RecipientType.TO)?.first()?.toString(),
        )
        assertEquals("scan completed", mailStub.sent.single().subject)

        // WEBHOOK：经 StubWebhookClient 捕获 POST，行 SENT
        val webhookRow = repository.findByRecipient("webhook").filter { it.type == "SCAN_COMPLETED" }
        assertEquals(1, webhookRow.size)
        assertEquals("SENT", webhookRow.single().status)
        assertEquals(1, webhookStub.posts.size)
        assertEquals("http://webhook.test/hook", webhookStub.posts.single().url)
        assertTrue(webhookStub.posts.single().body.contains("\"type\":\"SCAN_COMPLETED\""))
        assertTrue(webhookStub.posts.single().body.contains("\"recipientIds\":"))
    }

    @Test
    fun `delivery failure marks failed but publisher flow unaffected`() {
        mailStub.failWith = RuntimeException("smtp down")
        webhookStub.failWith = RuntimeException("http down")
        val assignee = ownerUser("m17dlv-owner2", "m17dlv-owner2@example.com")

        publisher.publishEvent(RemediationAssignedEvent(3L, 99L, assignee))   // 不抛 —— best-effort 实证

        // IN_APP 行不受渠道失败影响
        val inApp = repository.findByRecipient(assignee.toString()).filter { it.type == "REMEDIATION_ASSIGNED" }
        assertTrue(inApp.any { it.status == "SENT" })

        // EMAIL/WEBHOOK 行 FAILED + retry_count + error_message
        val emailRow = repository.findByRecipient("m17dlv-owner2@example.com").filter { it.type == "REMEDIATION_ASSIGNED" }.single()
        assertEquals("FAILED", emailRow.status)
        assertEquals(1, emailRow.retryCount)
        assertTrue(!emailRow.errorMessage.isNullOrBlank())

        val webhookRow = repository.findByRecipient("webhook").filter { it.type == "REMEDIATION_ASSIGNED" }.single()
        assertEquals("FAILED", webhookRow.status)
    }

    @TestConfiguration
    class DeliveryStubConfig {
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

- [ ] **Step 4: 事件接线集成测试**

创建 `M17NotificationEventIntegrationTest.kt`：

```kotlin
package com.example.compliance.notification

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.common.event.FindingRegressionEvent
import com.example.compliance.common.event.RemediationAssignedEvent
import com.example.compliance.common.event.RemediationCompletedEvent
import com.example.compliance.common.event.ReportSnapshotGeneratedEvent
import com.example.compliance.common.event.ScanCompletedEvent
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.example.compliance.project.domain.Project
import com.example.compliance.project.infrastructure.ProjectRepository
import com.example.compliance.user.domain.User
import com.example.compliance.user.infrastructure.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.assertTrue

/** M17 事件接线 e2e：publishEvent → 监听器（owner/assignee/actor 解析）→ notify → 落库。
 *  回归 emptyList bug 修复实证；webhook 未配置 → 无 WEBHOOK 行。
 *  数据前缀：项目 M17EVT1..4、用户 m17evt-*（跨类全局唯一，共享容器）。 */
class M17NotificationEventIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var publisher: ApplicationEventPublisher
    @Autowired lateinit var repository: NotificationRepository
    @Autowired lateinit var projectRepository: ProjectRepository
    @Autowired lateinit var userRepository: UserRepository

    private fun user(username: String): Long =
        userRepository.save(User().apply { this.username = username; passwordHash = "x" }).id!!

    private fun project(code: String, ownerId: Long?): Long =
        projectRepository.save(Project().apply { this.code = code; name = "m17"; ownerUserId = ownerId }).id!!

    @Test
    fun `scan completed and report snapshot notify project owner`() {
        val owner = user("m17evt-owner")
        val projectId = project("M17EVT1", owner)

        publisher.publishEvent(ScanCompletedEvent(901L, projectId, "SUCCESS"))
        publisher.publishEvent(ReportSnapshotGeneratedEvent(901L, projectId, "COMPLIANCE"))

        val rows = repository.findByRecipient(owner.toString())
        assertTrue(rows.any { it.type == "SCAN_COMPLETED" && it.title == "scan completed" && it.status == "SENT" })
        assertTrue(rows.any { it.type == "REPORT_SNAPSHOT_GENERATED" && it.title == "report snapshot generated" && it.status == "SENT" })
    }

    @Test
    fun `remediation assigned and completed notify their recipients`() {
        val assignee = user("m17evt-assignee")
        val actor = user("m17evt-actor")

        publisher.publishEvent(RemediationAssignedEvent(7L, 99L, assignee))
        publisher.publishEvent(RemediationCompletedEvent(7L, 99L, actor, assignee))

        assertTrue(repository.findByRecipient(assignee.toString()).any { it.type == "REMEDIATION_ASSIGNED" && it.title == "remediation assigned" })
        assertTrue(repository.findByRecipient(actor.toString()).any { it.type == "REMEDIATION_COMPLETED" && it.title == "remediation completed" })
        assertTrue(repository.findByRecipient(assignee.toString()).any { it.type == "REMEDIATION_COMPLETED" })
    }

    @Test
    fun `regression event notifies the project owner - emptyList bug fixed`() {
        val owner = user("m17evt-owner3")
        val projectId = project("M17EVT3", owner)

        publisher.publishEvent(FindingRegressionEvent(projectId, 8801L, listOf(2L, 3L)))

        val rows = repository.findByRecipient(owner.toString())
        assertTrue(rows.any { it.type == "FINDING_REGRESSION" && it.title == "finding regressed" && it.status == "SENT" })
    }

    @Test
    fun `no webhook row when url not configured`() {
        val owner = user("m17evt-owner4")
        val projectId = project("M17EVT4", owner)

        publisher.publishEvent(ScanCompletedEvent(902L, projectId, "FAILED"))

        assertTrue(repository.findByStatusAndChannel("PENDING", "WEBHOOK").none { it.type == "SCAN_COMPLETED" })
    }
}
```

- [ ] **Step 5: 运行 M17 集成测试（单独先跑，快速定位）**

Run: `./gradlew :app-server:test --tests "M17*IntegrationTest" --tests "M10NotificationEventIntegrationTest"`
Expected: 全绿（Testcontainers PG + 真实 SecurityConfig；含更新的 M10 测试）。

- [ ] **Step 6: 全量构建门**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（全量多模块；既有 260 + M17 新增全绿）。

- [ ] **Step 7: Commit**

```bash
git add -A app-server
git commit -m "test(notification): M17 e2e — notification center/delivery/event wiring integration tests + build gate (m17)"
```

---

## 自审记录（writing-plans checklist）

**1. Spec 覆盖**：
- §3.2 实体/迁移 → Task 1（readAt/errorMessage + V14）；Channel.WEBHOOK → Task 2 Step 1（Ruling PL-M17-4）。
- §3.3 投递核心 → Task 2（notify/EmailSender/WebhookSender/repository 写侧）；读侧查询 → Task 4。
- §3.4 事件（4 新 + 回归修复 + 豁免保留）→ Task 3（事件类 + 发布方 + 监听器 + 回归 owner 解析；标题/正文逐字取自 spec 表）。
- §3.5 站内信 API → Task 4（4 端点 + 分页硬化 + 404 + @PreAuthorize + AuthPrincipal.userId）。
- §3.6 模块依赖 → Task 1（user/project/mail + catalog）+ Task 5 Step 1（app-server testImplementation mail，Ruling PL-M17-7）。
- §4 测试三层 → Task 2/3/4 单测 + 切片 + Task 5 集成 + `./gradlew build` 门。
- §5 红线 → Global Constraints + 各 Task 落实（REQUIRES_NEW/best-effort 双层 runCatching/V14 唯一 DDL/零发布方新依赖）。

**2. 占位扫描**：无 TBD/TODO；所有代码步骤含完整实现与断言。

**3. 类型一致性**：
- `notify(notificationType: String, title: String, body: String, recipients: List<Long>)` 在 Task 2 定义、Task 3 监听器全部 6 个 handler 调用、Task 2/3 测试 verify —— 签名一致。
- `EmailSender.isAvailable()/send(Notification)`、`WebhookSender.isConfigured()/send(Notification, List<Long>, Instant)`、`WebhookClient.post(url, body): Boolean` —— Task 2 定义并全程复用。
- 事件字段：`ScanCompletedEvent(scanTaskId, projectId, status)` / `ReportSnapshotGeneratedEvent(snapshotId, projectId: Long?, snapshotType)` / `RemediationAssignedEvent(findingId, projectId, assigneeId)` / `RemediationCompletedEvent(findingId, projectId, actorId, assigneeId: Long?)` —— Task 3 事件类、发布方、监听器、测试四处一致（projectId 可空按 Ruling PL-M17-2）。
- Repository 读态方法名在 Task 4 Step 1 定义、Step 2 service 调用、Step 6 切片 mock —— 一致。
- 通知 type 字符串（SCAN_COMPLETED 等）在监听器与测试逐字一致。

**4. 依赖次序**：Task 1（依赖）→ Task 2（notify 需 user/mail）→ Task 3（监听器扩展需 Task 2 的 notify；发布方测试构造需 eventPublisher 新增）→ Task 4（API 复用 NotificationService）→ Task 5（集成消费 1-4）。每任务独立可测（Task 3 门禁不含 app-server 全量 Testcontainers；M10 集成测试在本任务改好、Task 5 执行，已在 Task 3 Step 9 注明）。
