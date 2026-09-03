# M10 增量实施计划 — phase-2 降级/park 项收口

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 收口 phase-2 三项延后工作（I1 module-admin 三端点、I4 NotificationSender 契约与豁免/回归事件接线、I8 verifyRechecking 按 requestId 精确作用域硬化）并清理两个信息级遗留（findByRuleCode JPQL、AuthPrincipal.hasRole 接线）。

**Architecture:** 七任务按依赖序推进。10.1 清理①（module-rule，隔离）；10.2 I8（module-result 端口+服务 + module-scan 编排器，确立新 verifyRechecking 签名）；10.3 I4 契约（module-notification：Channel/NotificationSender/NotificationService 升级/LogNotificationSender）；10.4 I4 事件接线（module-common 事件类 + result/remediation 发布 + notification 监听器，**零新增跨模块依赖边**）；10.5 I1 查询端口（module-common PageView + project/scan/result query ports，R-10.5-a：未分页过滤列表 + admin 侧切片）；10.6 I1 admin 三端点（module-admin + ADM-* 集成测试）；10.7 清理②（module-remediation markFixed 接线 hasRole）。

**Tech Stack:** Kotlin 2.0.21 / Spring Boot 3.3.5 / Spring Data JPA / Spring Security / Flyway / PostgreSQL 16（Testcontainers）/ JUnit 5 + MockK / MockMvc。全部 Gradle 命令用 `./gradlew`（wrapper 8.8）。

**Spec:**
- `docs/superpowers/specs/2026-09-03-code-compliance-platform-phase2-design.md`（phase-2 spec，§6.2/§6.4/§6.5 为本增量权威来源）
- `docs/superpowers/specs/2026-09-03-code-compliance-platform-m10-design.md`（本增量设计，用户 2026-09-03 确认；含 R-10.5-a 端口细化与 §3.3 零依赖边修正）

## Global Constraints

以下约束对每个任务隐式生效：

1. **Ruling #60（remote 约束）**：`origin` 保留；**绝不 push**；不新增 remote；不重命名分支；直接工作在 `main`（用户已授权 trunk + merge all），无 worktree。
2. **模块依赖（P2-D5）**：跨模块一律通过 **接口/端口 + 值类型（DTO）**，**绝不 import `@Entity`**。`module-admin` 依赖 project/scan/result 的 query ports；`module-notification` 依赖 common 事件类型（零新增边）。
3. **状态权威（P2-D4）**：`finding.status` 唯一权威；一切转移经 `FindingLifecyclePort.transition`；`remediation_task.status` 仅同事务镜像。
4. **Ruling #45/#52**：`ScanTaskService.startScan/triggerScan` 与编排器路径不添加 `@Transactional`；`transition` REQUIRED 自提交。
5. **Ruling #49**：HTTP 级集成测试命中真实安全链 → **必须 `@WithMockUser`**（admin 测试 `roles=["ADMIN"]`；负例 `roles=["DEVELOPER"]`）。
6. **Ruling #34**：`audit_log.detail` 为 JSONB —— 审计 detail 必须合法 JSON。
7. **MockK strict**：非 relaxed mock 未 stub 即调 → `MockKException`；`every` 必须精确覆盖。
8. **共享 Testcontainers**：app-server 集成测试共享一个 PostgreSQL 容器；数据全局唯一（新前缀 `ADM-*`/`NTF-*`）；`SmokeFirstClassOrderer` 不变。
9. **安全基线**：`@EnableMethodSecurity` 活跃；`GlobalExceptionHandler` 的 `@ExceptionHandler(AccessDeniedException)` → 403 保留；`/api/v1/admin/** → hasRole("ADMIN")` 的 URL 守卫已就位（不改动）。
10. **SDD 纪律**：subagent 不得再派 subagent；实现者**串行**派发（不并行）；每任务 RED→GREEN→全量 `./gradlew build` 回归→commit（未 push）。
11. **不进 M10**：PF-10（PREPARING/PARTIAL_SUCCESS/retry）、m1-m12 parks、真实渠道发送、`findingsByProject` 的 findAll（既有语义保留）。

---

## 文件结构总览

| 模块 | 新建/修改 | 职责 |
|---|---|---|
| module-common | `common/api/PageView.kt`（新）、`common/event/FindingRegressionEvent.kt`（新）、`common/event/RemediationWaiverEvent.kt`（新） | 共享分页 DTO + 事件类型 |
| module-project | `application/ProjectQueryPort.kt`（新）、`application/ProjectService.kt`（实现 count） | admin 项目计数 |
| module-scan | `application/ScanTaskQueryPort.kt`（新）、`application/ScanTaskService.kt`（实现 list）、`application/ScanOrchestrator.kt`（I8 目标集解析） | admin 任务列表 + I8 |
| module-result | `application/FindingLifecyclePort.kt`（+findingsGlobal、verifyRechecking 签名）、`application/FindingLifecycleService.kt`（实现 + 回归事件发布） | admin finding 查询 + I8 + I4 发布 |
| module-remediation | `application/RemediationService.kt`（豁免事件发布 + markFixed isAdmin）、`api/RemediationController.kt`（hasRole 接线） | I4 发布 + 清理② |
| module-notification | `domain/Channel.kt`（新）、`application/NotificationSender.kt`（新）、`application/NotificationService.kt`（升级）、`application/LogNotificationSender.kt`（新）、`application/NotificationEventListener.kt`（新）、`application/NotificationServiceTest.kt`（适配）、`application/LogNotificationSenderTest.kt`（新） | I4 契约 + 消费 |
| module-admin | `build.gradle.kts`（+3 依赖）、`application/AdminDashboardView.kt`（新）、`application/AdminQueryService.kt`（新）、`api/AdminController.kt`（新） | I1 三端点 |
| module-rule | `infrastructure/repos.kt`（+派生查询）、`application/RuleQueryService.kt`（findByRuleCode 委托）、`RuleQueryServiceTest.kt`（+verify no findAll） | 清理① |
| app-server test | `admin/M10AdminIntegrationTest.kt`（新）、`notification/M10NotificationEventIntegrationTest.kt`（新） | ADM-*/NTF-* 集成 |

---

## Task 10.1: 清理① — findByRuleCode 改 JPQL（module-rule）

**Files:**
- Modify: `module-rule/src/main/kotlin/com/example/compliance/rule/infrastructure/repos.kt`（`RuleDefinitionRepository` 加派生查询）
- Modify: `module-rule/src/main/kotlin/com/example/compliance/rule/application/RuleQueryService.kt:30-32`（`findByRuleCode` 委托）
- Modify: `module-rule/src/test/kotlin/com/example/compliance/rule/application/RuleQueryServiceTest.kt`（+2 测试）

**Interfaces:**
- Produces: `RuleDefinitionRepository.findFirstByRuleCodeAndStatus(ruleCode: String, status: RuleStatus): RuleDefinition?`（Hibernate 派生 `limit 1` 语义）
- Consumes: 现 `RuleQueryService.findByRuleCode(ruleCode: String): RuleDefinition?` 签名不变（module-scan 合规判定消费方零改动）。

> **为何捆绑**：查询方法与消费方同一提交 —— `findByRuleCode` 改委托后若查询方法缺失则编译失败；若查询方法先加而未改委托，`RuleQueryServiceTest` 的 `verify(exactly=0) { findAll() }` 仍红。二者一体。

- [ ] **Step 1: 写失败测试**（追加到 `RuleQueryServiceTest.kt`）

```kotlin
@Test
fun `findByRuleCode delegates to JPQL and never scans all rules`() {
    every { ruleRepository.findFirstByRuleCodeAndStatus("R1", RuleStatus.PUBLISHED) } returns published("R1")
    val result = service.findByRuleCode("R1")
    assertEquals("R1", result?.ruleCode)
    verify(exactly = 0) { ruleRepository.findAll() }
    verify(exactly = 1) { ruleRepository.findFirstByRuleCodeAndStatus("R1", RuleStatus.PUBLISHED) }
}

private fun published(ruleCode: String) = RuleDefinition().apply {
    this.ruleCode = ruleCode; status = RuleStatus.PUBLISHED
}
```

> 参考现有 `RuleQueryServiceTest` 的 mock 构造（strict mockk 的 `ruleRepository` 字段名以其实际声明为准；若字段名不同则对齐，勿臆造）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-rule:test --tests "*RuleQueryServiceTest*"`
Expected: FAIL —— `Unresolved reference 'findFirstByRuleCodeAndStatus'`（编译失败即 RED）。

- [ ] **Step 3: 实现**

`repos.kt` 的 `RuleDefinitionRepository` 接口内追加（镜像 F7 的 `findFirstByEngineAndEngineRuleIdAndStatus` 派生风格，ruleCode 非唯一则取 id 最小）：

```kotlin
// M10 清理①：findByRuleCode 原为 findAll().firstOrNull{} 内存过滤（每扫描逐调用 O(N)）。
// 派生查询 order by id limit 1 —— 保留 firstOrNull 语义（ruleCode 非唯一取最小 id）。
fun findFirstByRuleCodeAndStatus(ruleCode: String, status: RuleStatus): RuleDefinition?
```

`RuleQueryService.findByRuleCode` 改为：

```kotlin
/** 按平台规则号查已发布规则（module-scan 合规判定使用）。M10 清理①：委托派生查询，去除 findAll 内存过滤。 */
fun findByRuleCode(ruleCode: String): RuleDefinition? =
    ruleRepository.findFirstByRuleCodeAndStatus(ruleCode, com.example.compliance.rule.domain.RuleStatus.PUBLISHED)
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :module-rule:test --tests "*RuleQueryServiceTest*"` — Expected: PASS（新旧测试全绿）。

- [ ] **Step 5: 全量回归 + 提交**

Run: `./gradlew build` — Expected: BUILD SUCCESSFUL。
Commit（staged 仅 module-rule/src/main + module-rule/src/test）：
`fix(rule): replace RuleQueryService.findByRuleCode findAll() with JPQL (m10 cleanup)`

---

## Task 10.2: I8 — verifyRechecking 按 requestId 精确作用域（module-result + module-scan）

**Files:**
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecyclePort.kt`（`verifyRechecking` 签名 +`targetFindingIds` 参数）
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecycleService.kt:57-72`（实现收窄到目标集）
- Modify: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanOrchestrator.kt:118-121`（从 requestId 解析目标集）
- Modify: `module-result/src/test/kotlin/com/example/compliance/result/application/FindingLifecycleServiceTest.kt`（适配签名 + 新增目标集外不转移用例）

**Interfaces:**
- Produces: `fun verifyRechecking(projectId: Long, scanTaskId: Long, presentFindingIds: Set<Long>, targetFindingIds: Set<Long>): VerifyResult`
- Consumes: 编排器已持有 `task.requestId`（复扫任务 = `"recheck-f<findingId>"`，`RemediationService.kt:155`）；`findingsByProject` 等其余端口签名不动。

> **为何本任务先于 10.4**：10.4 的回归事件在 verifyRechecking 内发布，必须先确立新签名（10.4 实现者读到的是本任务的最终形态）。**语义记录（R-10.2-a）**：对 spec §4.3 字面（项目级遍历）的硬化偏离 —— 单复扫行为零变化；`targetFindingIds` 空集 → 不验证任何 finding（消除并发误 CLOSED 与乐观锁冲突）。

- [ ] **Step 1: 写失败测试**（`FindingLifecycleServiceTest` 追加 + 适配既有）

**① 适配既有 `verifyRechecking closes absent and regresses present findings`**（line 57）：调用改为 4 参，目标集 = 全部三个 finding（保持原语义不变）：

```kotlin
val result = service.verifyRechecking(5L, 99L, setOf(1L), targetFindingIds = setOf(1L, 2L, 3L))
```

**② 追加两个新测试**（实体构造/stub 模式对齐既有测试 line 28-54：`Finding().apply {}` 实体、`findById(any())` 从列表回答案、`statusRepository.save(any())` answers firstArg、`auditService` relaxed）：

```kotlin
@Test
fun `verifyRechecking only touches target finding ids`() {
    val f1 = Finding().apply { id = 1L; projectId = 9L; status = FindingStatus.RECHECKING; fingerprint = "t1" }
    val f2 = Finding().apply { id = 2L; projectId = 9L; status = FindingStatus.RECHECKING; fingerprint = "t2" }
    every { findingRepository.findAll() } returns listOf(f1, f2)
    every { findingRepository.save(any()) } answers { firstArg() }
    every { findingRepository.findById(any()) } answers { firstArg<Long>().let { id ->
        java.util.Optional.of(listOf(f1, f2).first { it.id == id })
    } }
    every { statusRepository.save(any()) } answers { firstArg() }

    // target 集只含 id=1 → 只有 id=1 被转移（缺席→CLOSED）；id=2 不被碰
    val result = service.verifyRechecking(9L, 99L, presentFindingIds = emptySet(), targetFindingIds = setOf(1L))

    assertEquals(VerifyResult(closed = 1, regressed = 0), result)
    assertEquals(FindingStatus.CLOSED, f1.status)
    assertEquals(FindingStatus.RECHECKING, f2.status)   // 目标集外保持 RECHECKING
    verify(exactly = 1) { findingRepository.findById(1L) }
    verify(exactly = 0) { findingRepository.findById(2L) }
}

@Test
fun `verifyRechecking with empty target ids is a no-op`() {
    val f1 = Finding().apply { id = 1L; projectId = 9L; status = FindingStatus.RECHECKING; fingerprint = "t1" }
    every { findingRepository.findAll() } returns listOf(f1)
    // 空 target → 无 transition → 不触碰任何 finding（不产生 save/findById）
    val result = service.verifyRechecking(9L, 99L, presentFindingIds = emptySet(), targetFindingIds = emptySet())
    assertEquals(VerifyResult(0, 0), result)
    verify(exactly = 0) { findingRepository.findById(any()) }
}
```

> 空 target 用例不 stub `save`/`findById`/`statusRepository.save` —— 若实现错误调了 transition，strict MockK 会因未 stub 抛异常（顺带证明 no-op）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-result:test --tests "*FindingLifecycleServiceTest*"`
Expected: FAIL —— 签名不匹配（既有调用 + 新测试均编译失败）。

- [ ] **Step 3: 实现**

`FindingLifecyclePort.kt`：

```kotlin
fun verifyRechecking(projectId: Long, scanTaskId: Long, presentFindingIds: Set<Long>, targetFindingIds: Set<Long>): VerifyResult
```

`FindingLifecycleService.verifyRechecking`：

```kotlin
/** 复扫验证：扫描完成后调用。R-10.2-a（M10 I8）：只验证 requestId 解析出的目标 finding
 *  （recheck-f<id>），不再遍历项目全部 RECHECKING —— 并发多复扫不再误 CLOSED / 乐观锁冲突。
 *  单复扫行为与 spec §4.3 一致；targetFindingIds 空 → 不验证（非复扫扫描 no-op）。 */
@Transactional
override fun verifyRechecking(projectId: Long, scanTaskId: Long, presentFindingIds: Set<Long>, targetFindingIds: Set<Long>): VerifyResult {
    var closed = 0
    var regressed = 0
    findingRepository.findAll()
        .filter { it.projectId == projectId && it.status == FindingStatus.RECHECKING && it.id in targetFindingIds }
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
```

`ScanOrchestrator.kt:118-121` 替换为：

```kotlin
val presentIds = findings.mapNotNull { it.id }.toSet()
// M10 I8：从复扫任务 requestId 解析目标 finding（recheck-f<id>）；非复扫任务空集 → 不验证。
// ScanTaskView.requestId 为非空 String（triggerScan 已 `?: ""` 兜底）——不需要 `?.`
val targetIds = task.requestId.takeIf { it.startsWith("recheck-f") }
    .removePrefix("recheck-f").toLongOrNull()?.let { setOf(it) } ?: emptySet()
val verify = lifecycleService.verifyRechecking(task.projectId, scanTaskId, presentIds, targetIds)
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :module-result:test --tests "*FindingLifecycleServiceTest*"` — Expected: PASS。

- [ ] **Step 5: 集成回归（复扫闭环不破）**

Run: `./gradlew :app-server:test --tests "*M7RemediationIntegrationTest*" --tests "*M9RecheckFailureCompensationTest*"` — Expected: PASS（复扫任务 requestId=recheck-fX → 目标=X → absent→CLOSED / present→CONFIRMED 语义不变）。

- [ ] **Step 6: 全量回归 + 提交**

Run: `./gradlew build` — Expected: BUILD SUCCESSFUL。
Commit（staged 仅 module-result/src/main、module-result/src/test、module-scan/src/main）：
`fix(result,scan): scope verifyRechecking to recheck requestId targets (m10 I8)`

---

## Task 10.3: I4 契约 — Channel / NotificationSender / NotificationService 升级 / LogNotificationSender（module-notification）

**Files:**
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/domain/Channel.kt`
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationSender.kt`
- Modify: `module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationService.kt`（实现接口，新签名）
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/application/LogNotificationSender.kt`
- Modify: `module-notification/src/test/kotlin/com/example/compliance/notification/application/NotificationServiceTest.kt`（适配新签名）
- Create: `module-notification/src/test/kotlin/com/example/compliance/notification/application/LogNotificationSenderTest.kt`

**Interfaces:**
- Produces: `enum class Channel { IN_APP, EMAIL, WECHAT, DINGTALK }`；`interface NotificationSender { fun send(channel: Channel, subject: String, body: String, recipients: List<Long>) }`（spec §6.4 逐字）；`NotificationService : NotificationSender` + `persist(channel, recipient, title, content)`；`LogNotificationSender : NotificationSender`（@Primary）。
- Consumes: 现 `Notification` 实体与 `NotificationRepository`（9.7 交付，不改）；10.4 的监听器按 `NotificationSender` 注入。

> **为何绑定**：`NotificationService` 移除旧 `send(String,String,String,String,String)` 签名与 `NotificationServiceTest` 适配必须同一提交（旧测试调旧签名会编译失败）。`LogNotificationSender` 委托 `NotificationService.persist`，一并落地。

- [ ] **Step 1: 写失败测试**（重写 `NotificationServiceTest`，strict mockk）

```kotlin
class NotificationServiceTest {
    private val repository = mockk<NotificationRepository>()
    private val service = NotificationService(repository)

    @Test
    fun `send persists one row per recipient pending then sent`() {
        every { repository.save(any<Notification>()) } answers { (firstArg<Notification>()).also { it.id = 3L } }
        service.send(Channel.IN_APP, "扫描完成", "detail", recipients = listOf(1L, 2L))
        verify(exactly = 4) { repository.save(any<Notification>()) }   // 2 接收人 × (PENDING + SENT)
    }

    @Test
    fun `persist writes channel as enum name and type EVENT`() {
        val slot = mutableListOf<Notification>()
        // 与既有 M9 测试同款 answer：set id 模拟保存（BaseEntity.id 默认 null）
        every { repository.save(any<Notification>()) } answers { firstArg<Notification>().also { it.id = 3L; slot += it } }
        service.persist(Channel.IN_APP, "1", "标题", "正文")
        assertEquals("IN_APP", slot.first().channel)
        assertEquals("EVENT", slot.first().type)
        assertEquals("SENT", slot.last().status)
        assertEquals(3L, slot.last().id)
    }
}
```

`LogNotificationSenderTest`：

```kotlin
class LogNotificationSenderTest {
    @Test
    fun `delegates to NotificationService after logging`() {
        val delegate = mockk<NotificationService>()
        every { delegate.send(any(), any(), any(), any()) } just Runs
        val sender = LogNotificationSender(delegate)
        sender.send(Channel.WECHAT, "主题", "正文", listOf(1L))
        verify(exactly = 1) { delegate.send(Channel.WECHAT, "主题", "正文", listOf(1L)) }
    }
}
```

> strict MockK：`NotificationService.send` 无返回值（Unit）→ `just Runs` 必须 stub，否则 `MockKException`。`Channel.WECHAT` 用例顺带证明非 IN_APP 渠道只走委托（日志占位）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-notification:test`
Expected: FAIL —— `Channel`/`NotificationSender` 未定义 + 旧 `send` 调用点编译失败。

- [ ] **Step 3: 实现**

`Channel.kt`：

```kotlin
package com.example.compliance.notification.domain

/** 通知渠道（spec §6.4：渠道仍延后 —— 真实发送仅 IN_APP 落库，其余仅日志占位）。 */
enum class Channel { IN_APP, EMAIL, WECHAT, DINGTALK }
```

`NotificationSender.kt`：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel

/** 通知发送契约（spec §6.4 逐字）。实现：LogNotificationSender（@Primary，写日志 + 落库）。 */
interface NotificationSender {
    fun send(channel: Channel, subject: String, body: String, recipients: List<Long>)
}
```

`NotificationService.kt`（整文件替换，保留 `list`）：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 通知服务：落库实现（站内信表预留）。M10 I4：升级为 NotificationSender 实现，每个接收人一行。 */
@Service
class NotificationService(
    private val repository: NotificationRepository,
) : NotificationSender {

    // send 经 LogNotificationSender 委托（Spring 代理）调用 → 代理上 @Transactional 生效，单事务批量落库
    // （self-invocation 绕过代理：persist 的 @Transactional 在 send 内不生效，故 send 自身必须标注）
    @Transactional
    override fun send(channel: Channel, subject: String, body: String, recipients: List<Long>) {
        recipients.forEach { persist(channel, it.toString(), subject, body) }
    }

    @Transactional
    fun persist(channel: Channel, recipient: String, title: String, content: String?): Notification {
        val pending = repository.save(Notification().apply {
            this.channel = channel.name
            this.recipient = recipient
            type = "EVENT"
            this.title = title
            this.content = content
            status = "PENDING"
        })
        // 渠道适配器扩展点：M9/M10 直接视为发送成功
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

`LogNotificationSender.kt`：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** 占位发送器（spec §6.4 点名）：写日志 + 委托落库（站内信表预留）。@Primary —— 事件消费方按接口注入拿到此 bean。 */
@Service
@org.springframework.context.annotation.Primary
class LogNotificationSender(
    private val notificationService: NotificationService,
) : NotificationSender {
    private val log = LoggerFactory.getLogger(LogNotificationSender::class.java)

    override fun send(channel: Channel, subject: String, body: String, recipients: List<Long>) {
        log.info("notification placeholder: channel={} subject={} recipients={}", channel, subject, recipients)
        notificationService.send(channel, subject, body, recipients)
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :module-notification:test` — Expected: PASS。

- [ ] **Step 5: 全量回归 + 提交**

Run: `./gradlew build` — Expected: BUILD SUCCESSFUL（app-server 集成套件不受影响——无消费方变化）。
Commit（staged 仅 module-notification/src/main + module-notification/src/test）：
`feat(notification): NotificationSender contract, Channel, NotificationService upgrade, LogNotificationSender (m10 I4)`

---

## Task 10.4: I4 事件接线 — common 事件类 + 发布方 + 监听器 + best-effort（module-common/result/remediation/notification + app-server 集成）

**Files:**
- Create: `module-common/src/main/kotlin/com/example/compliance/common/event/FindingRegressionEvent.kt`
- Create: `module-common/src/main/kotlin/com/example/compliance/common/event/RemediationWaiverEvent.kt`
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecycleService.kt`（注入 `ApplicationEventPublisher`，回归时发布）
- Modify: `module-remediation/src/main/kotlin/com/example/compliance/remediation/application/RemediationService.kt`（注入 `ApplicationEventPublisher`，WAIVED 时发布）
- Create: `module-notification/src/main/kotlin/com/example/compliance/notification/application/NotificationEventListener.kt`
- Modify: `module-result/src/test/kotlin/com/example/compliance/result/application/FindingLifecycleServiceTest.kt`（**构造 5 参适配** + regress 用例 publishEvent stub）
- Modify: `module-remediation/src/test/kotlin/com/example/compliance/remediation/application/RemediationServiceTest.kt`（**构造 4 参适配** + WAIVED 用例 publishEvent stub）
- Create: `app-server/src/test/kotlin/com/example/compliance/notification/M10NotificationEventIntegrationTest.kt`（NTF-*）

**测试适配（构造变化必须同一提交，否则两个测试类编译失败）：**

`FindingLifecycleServiceTest.kt`：
- line 24 构造改为 5 参：`FindingLifecycleService(findingRepository, statusRepository, evidenceRepository, auditService, eventPublisher)`，字段 `private val eventPublisher = mockk<org.springframework.context.ApplicationEventPublisher>()`。
- `verifyRechecking closes absent and regresses present` 用例（line 43-64，regressed=1 → 发布回归事件）：加 stub `every { eventPublisher.publishEvent(any()) } just Runs`；并追加断言 `verify { eventPublisher.publishEvent(match { it is FindingRegressionEvent && it.findingIds == listOf(1L) }) }`。
- Task 10.2 新增的 `only touches target`（无回归）与 `empty target`（无转移）用例不发布事件 → 不需要 stub（strict MockK 未调用即不报错）。

`RemediationServiceTest.kt`：
- line 23 构造改为 4 参：`RemediationService(taskRepository, lifecyclePort, triggerPort, eventPublisher)`，字段 `private val eventPublisher = mockk<org.springframework.context.ApplicationEventPublisher>()`。
- `terminal status requires reason and evidence...`（line 156-172，`to == WAIVED` → 发布豁免事件）：加 stub `every { eventPublisher.publishEvent(any()) } just Runs`；并追加断言 `verify { eventPublisher.publishEvent(match { it is RemediationWaiverEvent && it.findingId == 7L && it.reason == "risk accepted" }) }`。
- 其余用例不触 WAIVED → 不需要 stub。

**Interfaces:**
- Produces: `FindingRegressionEvent(projectId, scanTaskId, findingIds: List<Long>)`、`RemediationWaiverEvent(projectId, findingId, actorId, reason)`（module-common，value types）；`NotificationEventListener`（@Component + @EventListener）。
- Consumes: Task 10.2 的新 `verifyRechecking` 签名（回归事件发布点在其内部）；Task 10.3 的 `NotificationSender`/`Channel`。**零新增跨模块依赖边**（事件类在 common；发布方用 Spring `ApplicationEventPublisher`；监听器签名引用 common 类型）。

> **为何绑定**：事件类 + 发布点 + 监听器 + 集成测试同批 —— 发布点缺事件类编译失败；监听器缺发送契约编译失败；集成测试验证端到端闭环。best-effort 由监听器 `runCatching` 保证，测试证明失败不传播。

- [ ] **Step 1: 写失败集成测试**（`M10NotificationEventIntegrationTest.kt`，extends `AbstractIntegrationTest`，前缀 `NTF-*`；样式对齐既有 app-server 集成测试：`@Autowired` 注入 + `kotlin.test.assert*`）

```kotlin
package com.example.compliance.notification

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.common.event.FindingRegressionEvent
import com.example.compliance.common.event.RemediationWaiverEvent
import com.example.compliance.notification.infrastructure.NotificationRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.assertTrue

/** M10 I4 通知事件端到端（spec §6.4）：publishEvent → NotificationEventListener → NotificationSender → 落库。
 *  数据前缀 NTF-*（共享容器，全局唯一）。best-effort 失败注入在单测层（NotificationEventListenerTest），
 *  此处覆盖真实发布→落库闭环。 */
class M10NotificationEventIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var publisher: ApplicationEventPublisher
    @Autowired lateinit var notificationRepository: NotificationRepository

    @Test
    fun `waiver event is persisted as IN_APP notification for the actor`() {
        publisher.publishEvent(RemediationWaiverEvent(projectId = 9901L, findingId = 1L, actorId = 7L, reason = "业务豁免"))
        val rows = notificationRepository.findByRecipient("7")
        assertTrue(rows.any { it.type == "EVENT" && it.status == "SENT" && it.title == "finding waived" })
    }

    @Test
    fun `regression event with no recipients persists nothing`() {
        publisher.publishEvent(FindingRegressionEvent(projectId = 9902L, scanTaskId = 8801L, findingIds = listOf(2L, 3L)))
        // recipients 为空 → 零落库行（仅日志占位，不抛）—— 显式断言而非空跑
        assertTrue(notificationRepository.findByStatusAndChannel("SENT", "IN_APP").none { it.title == "finding regressed" })
    }
}
```

> `@SpringBootTest` 来自 AbstractIntegrationTest（MOCK env）；事件经 `ApplicationEventPublisher` **同步**触发监听器 → 断言即刻成立。`findByRecipient`/`findByStatusAndChannel` 为 NotificationRepository 既有方法。共享容器 + NTF-* 前缀保证数据隔离。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app-server:test --tests "*M10NotificationEventIntegrationTest*"`
Expected: FAIL —— `FindingRegressionEvent`/`RemediationWaiverEvent`/`NotificationEventListener` 未定义（编译失败）。

- [ ] **Step 3: 实现**

`module-common/common/event/FindingRegressionEvent.kt`：

```kotlin
package com.example.compliance.common.event

/** 回归检测事件（spec §6.4）：module-result 复扫验证命中回归时发布，module-notification 监听。 */
data class FindingRegressionEvent(
    val projectId: Long,
    val scanTaskId: Long,
    val findingIds: List<Long>,
)
```

`module-common/common/event/RemediationWaiverEvent.kt`：

```kotlin
package com.example.compliance.common.event

/** 豁免事件（spec §4.2 WAIVED 终态 + §6.4）：module-remediation 状态转移至 WAIVED 时发布。 */
data class RemediationWaiverEvent(
    val projectId: Long,
    val findingId: Long,
    val actorId: Long,
    val reason: String,
)
```

`FindingLifecycleService`（注入 `ApplicationEventPublisher`，构造参数追加；回归收集 id 列表）：

```kotlin
import com.example.compliance.common.event.FindingRegressionEvent
import org.springframework.context.ApplicationEventPublisher
// ...
@Service
class FindingLifecycleService(
    private val findingRepository: FindingRepository,
    private val statusRepository: FindingStatusSnapshotRepository,
    private val evidenceRepository: FindingEvidenceRepository,
    private val auditService: AuditService,
    private val eventPublisher: ApplicationEventPublisher,
) : FindingLifecyclePort {

    // ... verifyRechecking 内回归分支收集 regressedIds（继承 Task 10.2 的 4 参签名）：
    @Transactional
    override fun verifyRechecking(projectId: Long, scanTaskId: Long, presentFindingIds: Set<Long>, targetFindingIds: Set<Long>): VerifyResult {
        var closed = 0
        var regressed = 0
        val regressedIds = mutableListOf<Long>()
        findingRepository.findAll()
            .filter { it.projectId == projectId && it.status == FindingStatus.RECHECKING && it.id in targetFindingIds }
            .forEach { finding ->
                if (finding.id in presentFindingIds) {
                    transition(finding.id!!, FindingStatus.CONFIRMED, "regression_in_scan_$scanTaskId", null)
                    regressed++; regressedIds += finding.id!!
                } else {
                    transition(finding.id!!, FindingStatus.CLOSED, "verification_passed_in_scan_$scanTaskId", null)
                    closed++
                }
            }
        if (regressedIds.isNotEmpty()) {
            eventPublisher.publishEvent(FindingRegressionEvent(projectId, scanTaskId, regressedIds))
        }
        return VerifyResult(closed, regressed)
    }
    // ...
}
```

`RemediationService`（构造参数追加 `eventPublisher`；`status` 方法 `to == WAIVED` 时发布）。

> **前提**：现 `status()` 的 `mustGetFinding(findingId)`（line 138）是**丢弃返回值**的语句，`finding` 不在作用域 —— 必须先改为 `val finding = mustGetFinding(findingId)` 绑定（事件需要 `finding.projectId`）。`status()` 完整替换为：

```kotlin
import com.example.compliance.common.event.RemediationWaiverEvent
// ...
fun status(
    findingId: Long, to: FindingStatus, reason: String, evidenceType: String, evidenceRef: String, actorId: Long,
): FindingRemediationView {
    if (to !in TERMINAL_STATES) {
        throw BusinessException(400, "target status not terminal: $to")
    }
    if (reason.isBlank() || evidenceType.isBlank() || evidenceRef.isBlank()) {
        throw BusinessException(400, "reason and evidence required for terminal status")
    }
    val finding = mustGetFinding(findingId)
    lifecyclePort.addEvidence(findingId, evidenceType, evidenceRef, actorId)
    val view = mirrorTransition(findingId, to, reason, actorId)
    // M10 I4：WAIVED 终态发布豁免事件（best-effort，失败由监听器 runCatching 吞掉，不影响本事务）
    if (to == FindingStatus.WAIVED) {
        eventPublisher.publishEvent(RemediationWaiverEvent(finding.projectId, findingId, actorId, reason))
    }
    return view
}
```

`module-notification/NotificationEventListener.kt`：

```kotlin
package com.example.compliance.notification.application

import com.example.compliance.common.event.FindingRegressionEvent
import com.example.compliance.common.event.RemediationWaiverEvent
import com.example.compliance.notification.domain.Channel
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/** 通知事件消费（spec §6.4 best-effort：失败仅日志，不影响发布方主流程）。零跨模块依赖（事件类在 common）。 */
@Component
class NotificationEventListener(private val sender: NotificationSender) {
    private val log = LoggerFactory.getLogger(NotificationEventListener::class.java)

    @EventListener
    fun onRegression(e: FindingRegressionEvent) {
        runCatching {
            sender.send(Channel.IN_APP, "finding regressed", "回归：${e.findingIds.size} 个 finding 在扫描 ${e.scanTaskId} 复现", emptyList())
        }.onFailure { log.warn("regression notification failed: project={} scan={}", e.projectId, e.scanTaskId, it) }
    }

    @EventListener
    fun onWaiver(e: RemediationWaiverEvent) {
        runCatching {
            sender.send(Channel.IN_APP, "finding waived", "finding ${e.findingId} 被豁免（${e.reason}）", listOf(e.actorId))
        }.onFailure { log.warn("waiver notification failed: project={} finding={}", e.projectId, e.findingId, it) }
    }
}
```

- [ ] **Step 4: best-effort 单测**（`module-notification` 内新增 `NotificationEventListenerTest.kt`）

```kotlin
class NotificationEventListenerTest {
    @Test
    fun `sender failure does not propagate`() {
        val sender = mockk<NotificationSender>()
        every { sender.send(any(), any(), any(), any()) } throws RuntimeException("boom")
        val listener = NotificationEventListener(sender)
        listener.onWaiver(RemediationWaiverEvent(1L, 2L, 3L, "r"))   // 不抛 —— runCatching 兜底
        listener.onRegression(FindingRegressionEvent(1L, 2L, listOf(3L)))
    }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `./gradlew :module-notification:test :app-server:test --tests "*M10NotificationEventIntegrationTest*"` — Expected: PASS。

- [ ] **Step 6: 全量回归 + 提交**

Run: `./gradlew build` — Expected: BUILD SUCCESSFUL。
Commit（staged 仅 module-common/src/main、module-result/src/main、module-remediation/src/main、module-notification/src/main、module-notification/src/test、app-server/src/test）：
`feat(common,result,remediation,notification): notification events + best-effort wiring (m10 I4)`

---

## Task 10.5: I1 查询端口 — PageView + Project/Scan/Finding query ports（module-common/project/scan/result）

**Files:**
- Create: `module-common/src/main/kotlin/com/example/compliance/common/api/PageView.kt`
- Create: `module-project/src/main/kotlin/com/example/compliance/project/application/ProjectQueryPort.kt`
- Modify: `module-project/src/main/kotlin/com/example/compliance/project/application/ProjectService.kt`（`: ProjectQueryPort` + `count()`）
- Create: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanTaskQueryPort.kt`
- Modify: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanTaskService.kt`（`: ScanTaskQueryPort` + `list()`）
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecyclePort.kt`（+`findingsGlobal`）
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecycleService.kt`（实现 `findingsGlobal`）
- Modify: `module-project/src/test/.../ProjectServiceTest.kt`、`module-scan/src/test/.../ScanTriggerPortTest.kt`（ScanTaskService 单测的实际文件）、`module-result/src/test/.../FindingLifecycleServiceTest.kt`（各 +1 用例）

**Interfaces:**
- Produces: `PageView<T>(items, page, size, total)`（module-common）；`ProjectQueryPort.count(): Long`；`ScanTaskQueryPort.list(projectId: Long?, engine: String?, status: ScanTaskStatus?): List<ScanTaskView>`；`FindingLifecyclePort.findingsGlobal(projectId: Long?, status: FindingStatus?, severity: String?): List<FindingView>`。
- Consumes: R-10.5-a（未分页过滤列表 + admin 侧切片）；`ScanTaskView`/`FindingView` 既有值类型。

> **为何绑定**：PageView + 三个 query port 一起落地 —— 10.6 的 admin 端点依赖全部四物；单独落地任何一方都无消费方。各 port 有独立单测，评审面按模块分开。

- [ ] **Step 1: 写失败测试**（各模块追加）

`ProjectServiceTest`：

```kotlin
@Test
fun `count delegates to repository count`() {
    every { projectRepository.count() } returns 3L
    assertEquals(3L, service.count())
    verify(exactly = 1) { projectRepository.count() }
}
```

`ScanTriggerPortTest`（追加到既有类，复用其 7 参 `service` 构造 line 27-30；`list()` 只触 `scanTaskRepository`，其余 strict mock 不被调用即可）：

```kotlin
@Test
fun `list filters by project engine and status and maps to view`() {
    val tasks = listOf(
        ScanTask().apply { id = 1L; projectId = 9L; engine = "SEMGREP"; status = ScanTaskStatus.SUCCESS },
        ScanTask().apply { id = 2L; projectId = 9L; engine = "SEMGREP"; status = ScanTaskStatus.FAILED },
        ScanTask().apply { id = 3L; projectId = 8L; engine = "SEMGREP"; status = ScanTaskStatus.SUCCESS },
    )
    every { scanTaskRepository.findAll() } returns tasks
    val result = service.list(projectId = 9L, engine = "SEMGREP", status = ScanTaskStatus.SUCCESS)
    assertEquals(listOf(1L), result.map { it.id })
    verify(exactly = 1) { scanTaskRepository.findAll() }
}
```

`FindingLifecycleServiceTest`（追加到既有类；此时构造为 5 参 —— Task 10.4 已适配。**幸存实体的 `toView()` 会读 lateinit `engine/ruleCode/filePath`，必须赋值**，否则 UninitializedPropertyAccessException）：

```kotlin
@Test
fun `findingsGlobal filters by project status and severity`() {
    val f1 = Finding().apply { id = 1L; projectId = 9L; severity = "HIGH"; status = FindingStatus.NEW; engine = "SEMGREP"; ruleCode = "R1"; filePath = "A.java"; fingerprint = "g1" }
    val f2 = Finding().apply { id = 2L; projectId = 9L; severity = "LOW"; status = FindingStatus.NEW; engine = "SEMGREP"; ruleCode = "R1"; filePath = "A.java"; fingerprint = "g2" }
    val f3 = Finding().apply { id = 3L; projectId = 8L; severity = "HIGH"; status = FindingStatus.NEW; engine = "SEMGREP"; ruleCode = "R1"; filePath = "A.java"; fingerprint = "g3" }
    every { findingRepository.findAll() } returns listOf(f1, f2, f3)
    val result = service.findingsGlobal(projectId = 9L, status = FindingStatus.NEW, severity = "HIGH")
    assertEquals(listOf(1L), result.map { it.id })
}
```

> `findingsGlobal` 不发布事件 → 不需要 `eventPublisher` stub。`severity.equals(ignoreCase = true)` 过滤 —— 输入 `"HIGH"` 与实体 `"HIGH"` 精确命中。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-project:test --tests "*ProjectServiceTest*" :module-scan:test --tests "*ScanTaskServiceTest*" :module-result:test --tests "*FindingLifecycleServiceTest*"`
Expected: FAIL —— port/方法未定义。

- [ ] **Step 3: 实现**

`module-common/common/api/PageView.kt`：

```kotlin
package com.example.compliance.common.api

/** 统一分页响应（spec 统一 API：{items,page,size,total}）。 */
data class PageView<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)
```

`module-project/ProjectQueryPort.kt`：

```kotlin
package com.example.compliance.project.application

/** admin 项目计数端口（P2-D5：跨模块只暴露接口/值类型，禁止 @Entity）。 */
interface ProjectQueryPort {
    fun count(): Long
}
```

`ProjectService` 声明改为 `class ProjectService(...) : ProjectQueryPort`，追加：

```kotlin
override fun count(): Long = projectRepository.count()
```

`module-scan/ScanTaskQueryPort.kt`：

```kotlin
package com.example.compliance.scan.application

import com.example.compliance.scan.domain.ScanTaskStatus

/** admin 扫描任务查询端口（R-10.5-a：未分页过滤列表，分页/计数由 admin 侧切片）。 */
interface ScanTaskQueryPort {
    fun list(projectId: Long?, engine: String?, status: ScanTaskStatus?): List<ScanTaskView>
}
```

`ScanTaskService` 声明追加 `: ScanTaskQueryPort`，实现：

```kotlin
/** M10 I1：admin 任务列表 —— findAll + 过滤 + id 倒序（MVP 内存级，R-10.5-a）。 */
override fun list(projectId: Long?, engine: String?, status: ScanTaskStatus?): List<ScanTaskView> =
    scanTaskRepository.findAll()
        .asSequence()
        .filter { projectId == null || it.projectId == projectId }
        .filter { engine == null || it.engine == engine }
        .filter { status == null || it.status == status }
        .sortedByDescending { it.id }
        .map { ScanTaskView(it.id!!, it.projectId, it.engine, it.status, it.requestId ?: "") }
        .toList()
```

`FindingLifecyclePort` 追加：

```kotlin
/** M10 I1：全局 finding 过滤查询（R-10.5-a：未分页，分页/计数由 admin 聚合）。 */
fun findingsGlobal(projectId: Long?, status: FindingStatus?, severity: String?): List<FindingView>
```

`FindingLifecycleService` 实现（复用 `toView()`）：

```kotlin
override fun findingsGlobal(projectId: Long?, status: FindingStatus?, severity: String?): List<FindingView> =
    findingRepository.findAll()
        .asSequence()
        .filter { projectId == null || it.projectId == projectId }
        .filter { status == null || it.status == status }
        .filter { severity == null || it.severity.equals(severity, ignoreCase = true) }
        .map { it.toView() }
        .toList()
```

- [ ] **Step 4: 运行确认通过**

Run: 同 Step 2 命令 — Expected: PASS。

- [ ] **Step 5: 全量回归 + 提交**

Run: `./gradlew build` — Expected: BUILD SUCCESSFUL。
Commit（staged 仅 module-common/src/main、module-project/src/main、module-scan/src/main、module-result/src/main + 三个模块的 src/test）：
`feat(common,project,scan,result): admin query ports + PageView (m10 I1)`

---

## Task 10.6: I1 admin 三端点 + ADM-* 集成测试（module-admin + app-server）

**Files:**
- Modify: `module-admin/build.gradle.kts`（+ `module-project`、`module-scan`、`module-result`）
- Create: `module-admin/src/main/kotlin/com/example/compliance/admin/application/AdminDashboardView.kt`
- Create: `module-admin/src/main/kotlin/com/example/compliance/admin/application/AdminQueryService.kt`
- Create: `module-admin/src/main/kotlin/com/example/compliance/admin/api/AdminController.kt`
- Create: `app-server/src/test/kotlin/com/example/compliance/admin/M10AdminIntegrationTest.kt`（ADM-*）

**Interfaces:**
- Consumes: `ProjectQueryPort.count()`、`ScanTaskQueryPort.list(...)`、`FindingLifecyclePort.findingsGlobal(...)`、`PageView<T>`（均来自 10.5）。
- Produces: `AdminDashboardView(projectCount, scanTaskCount, findingCount, severityDistribution: Map<String, Int>)`；`GET /api/v1/admin/dashboard|scans|findings`（ADMIN-only，URL 守卫已就位）。

> **为何绑定**：端点 + 聚合服务 + 集成测试一体 —— 服务依赖四个 port，控制器依赖服务；集成测试验证安全链与数据正确性。URL 守卫 `/api/v1/admin/** → ADMIN` 已在 SecurityConfig（R-9.3-e），控制器类加 `@PreAuthorize("hasRole('ADMIN')")` 双保险（同 ApiTokenAdminController 模式）。

- [ ] **Step 1: 写失败集成测试**（`M10AdminIntegrationTest.kt`，extends `AbstractIntegrationTest`，前缀 `ADM-*`；样式对齐 `M9RbacIntegrationTest`：`@AutoConfigureMockMvc` + `mockMvc.perform(get(...)).andExpect(status().isX)` + `@WithMockUser`。注意 admin 端点**不解析 principal**，`@WithMockUser(roles=["ADMIN"])` 在安全链/方法级只查 ROLE_ADMIN 权限即可通过 —— 无需 AuthPrincipal postprocessor）

```kotlin
package com.example.compliance.admin

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** M10 I1 admin 三端点（spec §6.2）：ADMIN 可访问 / 非 ADMIN 403。
 *  数据前缀 ADM-*。响应为裸 DTO（RemediationController 惯例，无 ApiResponse 包）。 */
@AutoConfigureMockMvc
class M10AdminIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var projectService: ProjectService

    @BeforeEach
    fun seed() {
        projectService.create(CreateProjectCommand("ADM-P1", "M10 admin project", null, null))
    }

    @Test
    @WithMockUser(username = "adm-admin", roles = ["ADMIN"])
    fun `admin can view dashboard with counts and severity distribution`() {
        mockMvc.perform(get("/api/v1/admin/dashboard"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.projectCount").isNumber)
            .andExpect(jsonPath("$.severityDistribution").isMap)
    }

    @Test
    @WithMockUser(username = "adm-admin", roles = ["ADMIN"])
    fun `admin can list scans and findings with pagination`() {
        mockMvc.perform(get("/api/v1/admin/scans?page=0&size=10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isArray)
        mockMvc.perform(get("/api/v1/admin/findings?page=0&size=10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isArray)
    }

    @Test
    @WithMockUser(username = "adm-dev", roles = ["DEVELOPER"])
    fun `non-admin is forbidden on admin endpoints`() {
        mockMvc.perform(get("/api/v1/admin/dashboard"))
            .andExpect(status().isForbidden)
    }
}
```

> `CreateProjectCommand(code, name, null, null)` 4 参签名对齐 M9RbacIntegrationTest:161 的既有调用。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app-server:test --tests "*M10AdminIntegrationTest*"`
Expected: FAIL —— `AdminController`/端口未接（404 或编译失败）。

- [ ] **Step 3: 实现**

`module-admin/build.gradle.kts`：

```kotlin
dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-project"))
    implementation(project(":module-scan"))
    implementation(project(":module-result"))
}
```

`AdminDashboardView.kt`：

```kotlin
package com.example.compliance.admin.application

/** 管理后台仪表盘（spec §6.2）。 */
data class AdminDashboardView(
    val projectCount: Long,
    val scanTaskCount: Long,
    val findingCount: Long,
    val severityDistribution: Map<String, Int>,
)
```

`AdminQueryService.kt`：

```kotlin
package com.example.compliance.admin.application

import com.example.compliance.common.api.PageView
import com.example.compliance.project.application.ProjectQueryPort
import com.example.compliance.result.application.FindingLifecyclePort
import com.example.compliance.result.application.FindingView
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.scan.application.ScanTaskQueryPort
import com.example.compliance.scan.application.ScanTaskView
import com.example.compliance.scan.domain.ScanTaskStatus
import org.springframework.stereotype.Service

/** admin 聚合查询（spec §6.2）。分页/计数按 RemediationService.list 的 MVP 内存切片模式（R-10.5-a）。 */
@Service
class AdminQueryService(
    private val projectQuery: ProjectQueryPort,
    private val scanTaskQuery: ScanTaskQueryPort,
    private val lifecycle: FindingLifecyclePort,
) {
    fun dashboard(): AdminDashboardView {
        val allFindings = lifecycle.findingsGlobal(null, null, null)
        return AdminDashboardView(
            projectCount = projectQuery.count(),
            scanTaskCount = scanTaskQuery.list(null, null, null).size.toLong(),
            findingCount = allFindings.size.toLong(),
            severityDistribution = allFindings.groupingBy { it.severity }.eachCount(),
        )
    }

    fun scans(projectId: Long?, engine: String?, status: ScanTaskStatus?, page: Int, size: Int): PageView<ScanTaskView> =
        pageOf(scanTaskQuery.list(projectId, engine, status), page, size)

    fun findings(projectId: Long?, status: FindingStatus?, severity: String?, page: Int, size: Int): PageView<FindingView> =
        pageOf(lifecycle.findingsGlobal(projectId, status, severity), page, size)

    private fun <T> pageOf(all: List<T>, page: Int, size: Int): PageView<T> {
        val from = page.coerceAtLeast(0) * size.coerceAtLeast(1)
        val items = if (from >= all.size) emptyList() else all.subList(from, minOf(from + size, all.size))
        return PageView(items, page, size, all.size.toLong())
    }
}
```

`AdminController.kt`：

```kotlin
package com.example.compliance.admin.api

import com.example.compliance.admin.application.AdminDashboardView
import com.example.compliance.admin.application.AdminQueryService
import com.example.compliance.common.api.PageView
import com.example.compliance.result.application.FindingView
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.scan.application.ScanTaskView
import com.example.compliance.scan.domain.ScanTaskStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

/** 管理后台端点（spec §6.2，ADMIN-only —— SecurityConfig URL 守卫 + 方法注解双保险）。 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
class AdminController(private val query: AdminQueryService) {

    @GetMapping("/dashboard")
    fun dashboard(): AdminDashboardView = query.dashboard()

    @GetMapping("/scans")
    fun scans(
        @RequestParam(required = false) projectId: Long?,
        @RequestParam(required = false) engine: String?,
        @RequestParam(required = false) status: ScanTaskStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageView<ScanTaskView> = query.scans(projectId, engine, status, page, size)

    @GetMapping("/findings")
    fun findings(
        @RequestParam(required = false) projectId: Long?,
        @RequestParam(required = false) status: FindingStatus?,
        @RequestParam(required = false) severity: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageView<FindingView> = query.findings(projectId, status, severity, page, size)
}
```

> **响应形状（已核实）**：`ApiResponse` 仅用于 `GlobalExceptionHandler` 错误路径与 checklist 一处自定义返回；`RemediationController`（参照系）返回**裸 DTO**，无全局 `ResponseBodyAdvice`。admin 端点直接返回 `PageView<T>` / `AdminDashboardView`（Step 1 的 `$.projectCount`/`$.items` 断言以此为准）。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app-server:test --tests "*M10AdminIntegrationTest*"` — Expected: PASS（3/3）。

- [ ] **Step 5: 全量回归 + 提交**

Run: `./gradlew build` — Expected: BUILD SUCCESSFUL。
Commit（staged 仅 module-admin/src/main、module-admin/build.gradle.kts、app-server/src/test）：
`feat(admin): dashboard/scans/findings admin endpoints + integration tests (m10 I1)`

---

## Task 10.7: 清理② — AuthPrincipal.hasRole 接线（module-remediation）

**Files:**
- Modify: `module-remediation/src/main/kotlin/com/example/compliance/remediation/api/RemediationController.kt`（`actorAuthorities` → `isAdmin(auth)` 用 hasRole）
- Modify: `module-remediation/src/main/kotlin/com/example/compliance/remediation/application/RemediationService.kt`（`markFixed` 签名 `Set<String>` → `isAdmin: Boolean`）
- Modify: `module-remediation/src/test/kotlin/.../RemediationServiceTest.kt`（markFixed 调用点适配）
- Verify: `app-server/src/test/.../M9RbacIntegrationTest.kt`（保持绿 = 接线语义未变）

**Interfaces:**
- Produces: `markFixed(findingId: Long, actorId: Long, isAdmin: Boolean, evidenceType: String, evidenceRef: String)`；`RemediationController` 私有 `isAdmin(auth: Authentication?): Boolean = (auth?.principal as? AuthPrincipal)?.hasRole("ADMIN") ?: false`。
- Consumes: `AuthPrincipal.hasRole(role)`（module-common，F4 引入，M10 前无调用方）。

> **为何最后做**：`RemediationService` 已被 10.4 的豁免事件发布修改（不同方法，无冲突）；`RemediationController.fixed` 是本任务唯一改动端点。M9RbacIntegrationTest 的 ADMIN override / 非受让人 DEV 403 用例证明接线不改变语义。

- [ ] **Step 1: 写失败测试**（`RemediationServiceTest` 适配全部 5 个 markFixed 调用点 —— 编译 RED 预期：`Set<String>` 实参 → Boolean 类型不匹配）

```kotlin
// 既有 5 个调用点逐一替换（isAdmin 替代 Set<String>；isAdmin=false ≡ 旧 emptySet()，true ≡ 旧 setOf("ROLE_ADMIN")）：
// line 89/103/124/151：service.markFixed(7L, 9L, emptySet(), ...) → service.markFixed(7L, 9L, false, ...)
// line 143：service.markFixed(7L, 9L, setOf("ROLE_ADMIN"), ...) → service.markFixed(7L, 9L, true, ...)
```

> 只改实参类型，各用例断言/期望不动（`fixed requires evidence`、`markFixed rejects non-assignee non-admin`、`markFixed accepts the assignee`、`markFixed allows admin override`、`fixed without evidence`）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-remediation:test --tests "*RemediationServiceTest*"`
Expected: FAIL —— 类型不匹配。

- [ ] **Step 3: 实现**

`RemediationController`：

```kotlin
@PostMapping("/findings/{id}/fixed")
@PreAuthorize("isAuthenticated()")
fun fixed(@PathVariable id: Long, @RequestBody cmd: EvidenceCommand, auth: Authentication?): FindingRemediationView =
    service.markFixed(id, actorId(auth), isAdmin(auth), cmd.evidenceType, cmd.evidenceRef)

/** M10 清理②：hasRole 接线 —— ADMIN 覆写判定经 AuthPrincipal.hasRole（原 actorAuthorities 直查 authorities 死代码化）。 */
private fun isAdmin(auth: Authentication?): Boolean =
    (auth?.principal as? AuthPrincipal)?.hasRole("ADMIN") ?: false
```

> 移除或保留 `actorAuthorities(auth)`：若其他端点（`evidence` 等）不再使用则一并删除（YAGNI）；以编译后无未用告警为准。

`RemediationService.markFixed`：

```kotlin
@Transactional
fun markFixed(findingId: Long, actorId: Long, isAdmin: Boolean, evidenceType: String, evidenceRef: String): FindingRemediationView {
    val finding = mustGetFinding(findingId)
    if (evidenceType.isBlank() || evidenceRef.isBlank()) {
        throw BusinessException(400, "evidence required for fixed")
    }
    if (finding.status != FindingStatus.FIXING) {
        throw BusinessException(409, "finding not in FIXING state: $findingId")
    }
    val assignee = taskRepository.findByFindingId(findingId)?.assigneeUserId
    if (assignee != null && actorId != assignee && !isAdmin) {
        throw BusinessException(403, "only the assignee can mark fixed")
    }
    lifecyclePort.addEvidence(findingId, evidenceType, evidenceRef, actorId)
    return mirrorTransition(findingId, FindingStatus.FIXED, "fixed", actorId)
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :module-remediation:test --tests "*RemediationServiceTest*"` — Expected: PASS。

- [ ] **Step 5: RBAC 集成回归（语义未变）**

Run: `./gradlew :app-server:test --tests "*M9RbacIntegrationTest*"` — Expected: PASS（ADMIN override fixed + 非受让人 DEV 403 用例保持绿，证明 hasRole 接线与旧 authorities 直查等价）。

- [ ] **Step 6: 全量回归 + 提交**

Run: `./gradlew build` — Expected: BUILD SUCCESSFUL。
Commit（staged 仅 module-remediation/src/main + module-remediation/src/test）：
`fix(remediation): wire AuthPrincipal.hasRole into markFixed admin override (m10 cleanup)`

---

## 收尾

- [ ] **最终整分支评审**（SDD 流程）：`scripts/review-package PLAN_FILE BASE HEAD` 全量 diff → 最强模型 reviewer → 裁决 → 若有 MUST-FIX 一轮 fix dispatch + scoped re-review。
- [ ] **finishing-a-development-branch**：M10 全部 commit 在 `main`（用户已授权 trunk），merge 为 no-op；不 push（Ruling #60）；ledger 记录 M10 完成。
- [ ] **M10 完成标准复核**（spec §1）：I1 三端点落地 + I4 契约/事件接线 + I8 作用域硬化 + 清理①② 落地 + 全量 build 绿 + phase-2 既有集成套件零回归。
