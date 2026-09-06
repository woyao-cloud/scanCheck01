# 代码合规扫描平台 M17 — 通知服务（站内信中心 + EMAIL/Webhook 投递 + 事件接线）设计

> 本文档是 M17 里程碑的整体规划设计：把 M10 的「占位通知模块」升级为真正的通知服务 —— 站内信中心（面向当前用户 API）、EMAIL/Webhook 真实渠道投递（渠道适配器 + 状态机）、事件接线扩展（扫描完成/报告就绪/整改流转 + 回归收件人修复）。锁定目标、范围、关键设计决策与测试策略。里程碑关闭时另行出具实施计划，按序交付、逐里程碑确认。

**基线 spec：** `docs/superpowers/specs/2026-09-02-code-compliance-platform-design.md`（全局约束/枚举/模块划分/安全红线继续约束本阶段）
**既有阶段 spec：** `2026-09-03-code-compliance-platform-phase2-design.md`（M6–M9，§6.4 module-notification）、`2026-09-03-code-compliance-platform-m10-design.md`（M10 收口 I4 占位）、`2026-09-04-code-compliance-platform-m12-m14-design.md`、`2026-09-04-code-compliance-platform-m15-design.md`、`2026-09-05-code-compliance-platform-m16-design.md`
**前置交付：** M16（HEAD `98a4592`，已 push `origin/main`）。M16 范围经用户确认分三批：M16 = 报表导出 + 审计查询（已交付）；**M17 = 通知服务**（本 spec）；M18 = Quartz 定时扫描调度。

---

## 1. 目标与范围

phase-2 spec §6.4 与 M10 I4 交付了**占位**通知：`NotificationSender` 契约 + `LogNotificationSender`（@Primary，写日志 + 落库，status 立即置 SENT）+ 2 个事件监听（回归/豁免）。「站内信表预留」——即只有一张表，**无已读态、无面向用户的 API、无真实渠道投递**；且回归事件收件人传 `emptyList()`（实际是 bug，通知无人接收）。M17 把占位升级为真正的通知服务。

**本里程碑目标：**
1. **站内信中心**：预留表转正——当前登录用户可查询「我的」通知（分页 + 未读计数 + 标记已读），补 `read_at` 已读态。
2. **真实渠道投递**：EMAIL（JavaMailSender/SMTP）+ Webhook（RestClient/HTTP）渠道适配器，状态机 `PENDING → SENT/FAILED`（+ retry_count + error_message）；IN_APP 落库即投递。WECHAT/DINGTALK 仍为枚举预留。
3. **事件接线扩展**：新增 4 个 common 事件（扫描完成 / 报告快照就绪 / 整改指派 / 整改完成），修复回归事件收件人（`emptyList` → 项目负责人），豁免事件保留。**收件人解析集中于 notification 模块**（发布方零新依赖边）。
4. **红线保持**：通知写入 best-effort（`runCatching` + `REQUIRES_NEW` 独立事务，失败不影响发布方主流程）；`audit_log` 只增不改红线不变；无硬编码合规规则；历史扫描结果不可改。

### 1.1 非目标（YAGNI，仍延后）

- **通知模板管理**（版本化模板 + 占位符）——标题/正文暂为监听器内固定字符串；模板管理另立里程碑
- **企微/钉钉/飞书真实渠道**——`Channel` 枚举保留 WECHAT/DINGTALK，无 sender
- **后台调度重试**（定时重发 FAILED/PENDING）——M17 只记录 `retry_count`；调度能力归 M18 Quartz
- **异步投递**（@Async / RabbitMQ/Kafka）——保持 `@EventListener` 同步 best-effort（M10 明确「MVP 不引入 @Async」；MQ 是平台预留能力）
- **通知偏好设置**（用户订阅哪些事件/渠道）、**admin 全量通知视图**、**通知归档/保留策略**
- **Webhook 多目标/目标表**——M17 单全局 URL（`compliance.notification.webhook-url` 配置属性，未配置则跳过）
- **邮件模板/附件/富文本**——纯文本标题 + 正文
- **OpenAPI/对外 API 通知侧**

---

## 2. 决策记录（M17 新增/变更）

| 编号 | 问题 | 决策 |
|---|---|---|
| **R-M17-D1** | 模块依赖 | module-notification 增 `implementation(project(":module-user"))`（邮箱解析）+ `implementation(project(":module-project"))`（负责人解析）+ `implementation(libs.spring.boot.starter.mail)`（JavaMailSender）。web 能力经 module-common 的 `api(spring-boot-starter-web)` 已透传（控制器可用，无需新增）。catalog 需补 `spring-boot-starter-mail` 条目（Boot BOM 管版本）。依赖无环：user/project 仅依赖 common。 |
| **R-M17-D2** | 收件人解析宿主 | **通知模块解析**（用户选定 B）：事件只携带标识（projectId / assigneeId / actorId）。解析分两层、全部在 notification 模块内：**监听器**做「事件→收件人 userIds」策略映射（scan/report/回归 → `ProjectRepository.findById(projectId)?.ownerUserId`，owner 为 null 则跳过；指派 → assigneeId；完成 → actorId + assigneeId 去重；豁免 → actorId）；**`NotificationService.notify`** 做「userId → email」(`UserRepository.findById(userId)?.email` 决定 EMAIL 行) + 渠道 fan-out。发布方（module-result/report/remediation）**零新依赖边**；「通知谁」的策略集中在 notification 模块一处。 |
| **R-M17-D3** | 数据模型 | `Notification` 实体增 `readAt: Instant?`（`read_at`）+ `errorMessage: String?`（`error_message`）。**V14 DDL**（Flyway）：`ALTER TABLE notification ADD COLUMN read_at TIMESTAMP; ALTER TABLE notification ADD COLUMN error_message TEXT;`。保持**单行/渠道/收件人**模型（不新建 delivery 表）。 |
| **R-M17-D4** | 投递模型 | 两阶段：**persist**（`NotificationService.notify`，REQUIRES_NEW 独立事务，best-effort 隔离保持）→ **deliver**（各渠道 sender 尝试投递并更新状态，失败置 `FAILED` + `retry_count+1` + `error_message`，不抛出）。渠道策略统一无 per-event 配置：IN_APP 每收件人一行、落库即 SENT；EMAIL 仅对有 email 用户建行（recipient=email）；WEBHOOK 每事件一行（recipient=`webhook`），`compliance.notification.webhook-url` 未配置则跳过。**无后台重试**（归 M18）。 |
| **R-M17-D5** | 渠道适配器 seam | `EmailSender` 依赖 Spring `JavaMailSender` 接口（spring-boot-starter-mail 提供）；`WebhookSender` 依赖自定义 `WebhookClient` 接口（生产实现 `RestWebhookClient` 用 RestClient POST）。测试注入 stub（`StubJavaMailSender` 捕获 MimeMessage / `StubWebhookClient` 捕获请求）——**不引入 Greenmail/MockWebServer**。 |
| **R-M17-D6** | 状态机 | `PENDING → SENT / FAILED`。IN_APP 落库即 SENT（行本身即投递）；EMAIL/WEBHOOK persist 为 PENDING，sender 尝试后置 SENT（+ sentAt）/ FAILED（+ retry_count + error_message）。无定时重试。 |
| **R-M17-D7** | 站内信 API | module-notification `api/` 控制器，路由 `/api/v1/notifications`（避开 `/api/v1/admin/**`——SecurityConfig 整锁 ADMIN 会误伤普通用户；落到 `anyRequest().authenticated()`）。方法级 `@PreAuthorize("isAuthenticated()")`，全部按 `AuthPrincipal.userId` 过滤（当前用户自助，无 admin 全量视图）。 |
| **R-M17-D8** | 事件集 | module-common/event/ 新增 4 事件（ScanCompleted / ReportSnapshotGenerated / RemediationAssigned / RemediationCompleted，各字段见 §3.4）；`FindingRegressionEvent` 负载不变、监听器改为解析 owner（修复 `emptyList` bug）；`RemediationWaiverEvent` 保留。 |
| **R-M17-D9** | 测试三层 | 单测（NotificationService / 监听器 / EmailSender / WebhookSender / 控制器切片）+ 集成（`M17*` 集成测试，`M17-*`/`NTF-M17-*` 数据前缀，live Testcontainers PG + stub 渠道）+ `./gradlew build` 门禁。 |

---

## 3. 组件与数据流

### 3.1 总览

```
┌─ 站内信中心（module-notification api/）──────────────────┐
│  GET  /api/v1/notifications?page=&size=&unreadOnly=       │
│  GET  /api/v1/notifications/unread-count                  │
│  POST /api/v1/notifications/{id}/read                     │
│  POST /api/v1/notifications/read-all                      │
│  → isAuthenticated + AuthPrincipal.userId 过滤（IN_APP）   │
└──────────────────────────────────────────────────────────┘
┌─ 投递（module-notification application/）────────────────┐
│  事件 → NotificationEventListener → NotificationService   │
│    .notify(type,title,body,recipients)                    │
│   ① 解析收件人（owner/assignee/actor + email）             │
│   ② persist：IN_APP(每收件人)→SENT；EMAIL(有邮箱)/WEBHOOK  │
│      →PENDING 行（REQUIRES_NEW 独立事务，best-effort）     │
│   ③ deliver：EmailSender(JavaMailSender) /                │
│      WebhookSender(RestClient) → SENT/FAILED + 重试计数    │
└──────────────────────────────────────────────────────────┘
┌─ 事件发布（各模块 publish，common 事件类）────────────────┐
│  module-scan       ScanCompletedEvent（任务终态）          │
│  module-report     ReportSnapshotGeneratedEvent（快照生成）│
│  module-remediation RemediationAssignedEvent /            │
│                    RemediationCompletedEvent（指派/完成） │
│  module-result     FindingRegressionEvent（既有，修复收件人）│
└──────────────────────────────────────────────────────────┘
```

### 3.2 实体与迁移

`module-notification/.../domain/Notification.kt`（既有实体增两字段）：

```kotlin
@Column(name = "read_at")
var readAt: Instant? = null
@Column(name = "error_message")
var errorMessage: String? = null
```

**V14__notification_read_state.sql**（app-server/src/main/resources/db/migration/）：

```sql
ALTER TABLE notification ADD COLUMN read_at TIMESTAMP;
ALTER TABLE notification ADD COLUMN error_message TEXT;
```

（`notification` 表结构与 V11 索引保持不动；`BaseEntity` 提供 id/created_at/updated_at。）

### 3.3 投递核心（module-notification application/）

| 组件 | 职责 |
|---|---|
| `NotificationService.notify(notificationType: String, title: String, body: String, recipients: List<Long>)` | 收件人去重/过滤 → 解析 email → 每收件人落 IN_APP 行（SENT+sentAt）→ 有 email 者落 EMAIL 行（PENDING，recipient=email）→ webhook 已配置则落 WEBHOOK 行（PENDING，recipient=`webhook`）→ deliver 各非 IN_APP 行。`@Transactional(REQUIRES_NEW)`。best-effort 失败仅日志，不抛出（发布方隔离保持）。 |
| `EmailSender`（@Service） | 渠道适配器：依赖 `JavaMailSender`（seam）。对 EMAIL 行构建 `MimeMessage`（to=recipient、subject=title、text=body）发送；成功 → SENT+sentAt；失败 → FAILED+retry_count+1+error_message。 |
| `WebhookClient`（接口）+ `RestWebhookClient`（@Service，RestClient POST）+ `WebhookSender`（@Service） | Webhook 适配器：`WebhookSender` 依赖 `WebhookClient`（seam）。POST `compliance.notification.webhook-url`，body 为标准化 JSON `{"type":<type>,"title":<title>,"body":<body>,"recipientIds":[<Long>],"occurredAt":<ISO-8601>}`；成功/失败同上状态流转。url 未配置 → 不建 WEBHOOK 行。 |
| `NotificationRepository` | 既有（`findByRecipient` 保留）；增 read 态查询：`findByRecipientAndChannelAndReadAtIsNull`、`countByRecipientAndChannelAndReadAtIsNull`、`markRead`（bulk update，`@Modifying`）。 |

**收件人解析**（R-M17-D2，两层都在 notification 模块：监听器做「事件→userId」映射与 owner 查询，`NotificationService.notify` 做「userId→email」与 fan-out）：

```
recipients（入参，已含 owner/assignee/actor 解析结果）→
  per recipient: IN_APP 行（recipient = userId.toString()）
  email = UserRepository.findById(userId)?.email（blank → 无 EMAIL 行）
webhook url 非空 → WEBHOOK 行
```

**监听器**（`NotificationEventListener` 扩展）：每个事件 handler `runCatching { notify(...) }.onFailure { log.warn(...) }`（best-effort，既有模式）。

### 3.4 事件接线

**module-common/event/ 新增 4 个事件类**（`data class`，与既有 `FindingRegressionEvent`/`RemediationWaiverEvent` 同风格——普通值类型，非 `ApplicationEvent`）：

| 事件类 | 字段 | 发布方（接线点） | 收件人策略 |
|---|---|---|---|
| `ScanCompletedEvent` | `scanTaskId: Long, projectId: Long, status: String` | module-scan `ScanTaskService`——任务达终态处（COMPLETED/FAILED 状态流转完成后） | `project.ownerUserId`（null → 无收件人，不通知） |
| `ReportSnapshotGeneratedEvent` | `snapshotId: Long, projectId: Long, snapshotType: String` | module-report `ReportGenerationService`——快照 persist 后 | `project.ownerUserId` |
| `RemediationAssignedEvent` | `findingId: Long, projectId: Long, assigneeId: Long` | module-remediation `RemediationService`——assign 命令成功处 | `assigneeId` |
| `RemediationCompletedEvent` | `findingId: Long, projectId: Long, actorId: Long, assigneeId: Long?` | module-remediation `RemediationService`——fixed 命令成功处 | `actorId` + `assigneeId`（去重，null 跳过） |

**既有事件行为变更**：
- `FindingRegressionEvent(projectId, scanTaskId, findingIds)` —— 负载不变；监听器把 `emptyList()` 改为解析 `project.ownerUserId` 为收件人（**修复 M10 遗留 bug**）
- `RemediationWaiverEvent` —— 保留（actorId 收件人不变）

**标题/正文模板（监听器内固定字符串，模板管理延后）**：

| 事件 | title | body |
|---|---|---|
| 扫描完成 | `"scan completed"` | `"扫描 ${scanTaskId} 完成：${status}"` |
| 报告就绪 | `"report snapshot generated"` | `"快照 ${snapshotId} 已生成（${snapshotType}）"` |
| 整改指派 | `"remediation assigned"` | `"finding ${findingId} 已指派给你"` |
| 整改完成 | `"remediation completed"` | `"finding ${findingId} 已标记完成"` |
| 回归 | `"finding regressed"`（既有） | 既有文案（收件人修复） |
| 豁免 | `"finding waived"`（既有） | 既有文案 |

### 3.5 站内信 API（module-notification api/）

路由 `/api/v1/notifications`，`@RestController` + `@PreAuthorize("isAuthenticated()")`。全部按当前用户 `AuthPrincipal.userId` 过滤 IN_APP 行。

| 端点 | 语义 | 返回 |
|---|---|---|
| `GET /api/v1/notifications?page=0&size=20&unreadOnly=false` | 我的 IN_APP 通知分页（page<0→400、size 钳 [1,100]、**id DESC**，镜像 report list C2/审计查询 D6 硬化）；`unreadOnly=true` 只列未读 | `ApiResponse<PageView<NotificationView>>` `{items,page,size,total}` |
| `GET /api/v1/notifications/unread-count` | 我的未读计数 | `ApiResponse<{count}>` |
| `POST /api/v1/notifications/{id}/read` | 标记单条已读；**仅本人**（`recipient != 我` 或不存在 → `BusinessException(404)`）；已读幂等（已读再读仍 200） | `ApiResponse.ok` |
| `POST /api/v1/notifications/read-all` | 我的全部标记已读 | `ApiResponse<{count}>` |

`NotificationView`：`(id, type, title, content, channel, status, readAt, createdAt)`，`companion object fun from(e: Notification)`。

**安全**：无 SecurityConfig 改动——`/api/v1/notifications/**` 落到 `anyRequest().authenticated()`（SecurityConfig 既有），方法级 `@PreAuthorize("isAuthenticated()")` 显式声明意图（镜像审计控制器方法级注解模式）。

### 3.6 模块依赖（最终）

```
module-notification:
  implementation(project(":module-common"))      # 既有（api 透传 web/security/jpa/validation）
  implementation(project(":module-user"))        # R-M17-D1 新增（邮箱解析）
  implementation(project(":module-project"))     # R-M17-D1 新增（负责人解析）
  implementation(libs.spring.boot.starter.mail)  # R-M17-D1 新增（JavaMailSender；catalog 补条目）

gradle/libs.versions.toml:
  [libraries] spring-boot-starter-mail = { module = "org.springframework.boot:spring-boot-starter-mail" }
```

发布方（scan/report/remediation/result）**零新依赖边**（R-M17-D2）——事件类在 module-common，发布方已依赖 common。

---

## 4. 测试策略

### 4.1 单元测试

| 测试 | 覆盖 |
|---|---|
| `NotificationServiceTest`（MockK，module-notification） | 收件人去重/过滤、email 解析（有/无/blank）、IN_APP 恒建行且 SENT、EMAIL 仅对有邮箱者建行、WEBHOOK 按 url 配置建行、状态流转、`REQUIRES_NEW` 隔离（模拟异常不污染） |
| `NotificationEventListenerTest`（MockK） | 4 新 handler + 回归/豁免：事件 → `notify(type,title,body,recipients)` 正确映射；best-effort（`notify` 抛异常 → 日志不传播） |
| `EmailSenderTest`（MockK/StubJavaMailSender） | MimeMessage 构建（to/subject/text）、成功 → SENT+sentAt、失败 → FAILED+retry_count+1+error_message |
| `WebhookSenderTest`（StubWebhookClient） | POST 目标 url + JSON body 形状、成功/失败状态流转 |
| `NotificationControllerTest`（@WebMvcTest 切片 + TestConfig 标记 + `@Import(GlobalExceptionHandler::class)`——M16 确立模式） | 四端点形状、page<0→400 `{code:400}`、size 钳制、read 非本人→404 |

### 4.2 集成测试（app-server，live Testcontainers PG + 真实 SecurityConfig）

| 测试 | 覆盖 |
|---|---|
| `M17NotificationCenterIntegrationTest` | publish 事件（或 seed 行）→ 认证用户列表分页/未读计数/标记已读/read-all；**RBAC**：任意认证用户 200、未认证 401；read 非本人 → 404（真实 SecurityConfig + @PreAuthorize） |
| `M17NotificationDeliveryIntegrationTest` | EMAIL 经测试 bean 的 `StubJavaMailSender`（捕获 MimeMessage，断言 to/subject/body）→ 行 SENT；Webhook 经 `StubWebhookClient`（断言 POST body）→ 行 SENT；`notify` 抛异常 → 发布方主流程不受影响（best-effort 实证） |
| `M17NotificationEventIntegrationTest` | publish `ScanCompletedEvent`（seed 项目带 owner）→ owner 收到 IN_APP 行 + EMAIL 行（stub）；publish `RemediationAssignedEvent` → assignee 收到；回归事件 → owner 收到（**emptyList bug 修复实证**）；webhook url 未配置 → 无 WEBHOOK 行 |

数据前缀：项目 `M17RP`/`M17RP2`…、用户 `m17-*`、通知 module 标记 `NTF-M17-*`——与既有里程碑不相交。共享 Testcontainers（max_connections=300 保持）。

### 4.3 门禁

`./gradlew build` → BUILD SUCCESSFUL（全量多模块，既有 260 + M17 新增全绿）。

---

## 5. 里程碑边界（任务方向，实施计划将固化为 5 个任务）

1. **实体 + 迁移 + 依赖**：Notification 增 readAt/errorMessage + V14 DDL + module-notification build.gradle（user/project/mail）+ catalog 补 mail 条目 + 单测
2. **投递核心**：`NotificationService.notify`（收件人解析 + fan-out + 状态机）+ `EmailSender` + `WebhookClient`/`RestWebhookClient`/`WebhookSender` + 单测
3. **事件接线**：4 新 common 事件 + 发布方 publish（scan/report/remediation）+ 监听器扩展 + 回归修复 + 单测
4. **站内信 API**：NotificationController（list/unread-count/read/read-all）+ Repository 查询 + 切片测试
5. **集成测试 + 全量构建门**

**红线（延续）**：通知写入 best-effort 不影响发布方；`audit_log` 只增不改；无硬编码合规规则；历史扫描结果不可改；无除 V14 外的 DDL。
