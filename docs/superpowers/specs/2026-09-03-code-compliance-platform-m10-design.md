# M10 增量设计 — phase-2 降级/park 项收口

> **状态**：草案（待用户审阅）
> **权威来源**：本设计依据 `2026-09-03-code-compliance-platform-phase2-design.md` 的 §6.2（module-admin）、§6.4（module-notification）、§6.5（tech debt）与 phase-2 ledger（`.superpowers/sdd/2026-09-03-code-compliance-platform-phase2/progress.md`）中 I1 / I4 / I8 的降级与 park 裁决。冲突时以 phase-2 spec 与 ledger 为准。
> **范围（用户 2026-09-03 确认）**：核心 3 项（I1、I4、I8）+ 2 清理项（findByRuleCode JPQL、AuthPrincipal.hasRole 接线）。**接线机制**：Spring ApplicationEvent。

---

## 1. 目标与完成标准

在 phase-2（M6-M9）完成的基础上，收口三项延后工作并清理两个信息级遗留，使 phase-2 spec 的 §6.2 / §6.4 验收项落地、I8 并发硬化达成：

1. **I1 — module-admin 三端点**（spec §6.2）落地，ADMIN-only，集成测试覆盖。
2. **I4 — NotificationSender 契约 + 豁免/回归事件接线**（spec §6.4）落地，best-effort 调用不影响主流程。
3. **I8 — verifyRechecking 按 requestId 精确作用域**硬化，消除多复扫并发下的误 CLOSED 与乐观锁冲突。
4. **清理①**：`RuleQueryService.findByRuleCode` 的 `findAll()` 内存过滤改 JPQL（§6.5 I3 全量闭合）。
5. **清理②**：`AuthPrincipal.hasRole()` 从「未用死代码」变为有真实调用方（接线到 markFixed 的 ADMIN 覆写判定）。

**验收**：每任务结束 `./gradlew build` 全绿；M10 完成 = 上述 5 项全部落地 + 全量回归（含 phase-2 既有 30 个 app-server 集成测试）。

---

## 2. I1 — module-admin 三端点（spec §6.2）

### 2.1 现状

- `module-admin` 仅 scaffold：`package-info.kt` + `build.gradle.kts`（依赖 `module-common`）。
- SecurityConfig 已含 `auth.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")`（R-9.3-e 就位）——**端点鉴权零改动**。
- 跨模块数据源现状：`ProjectService.list(): List<Project>`（返回 @Entity，P2-D5 不可直接跨模块）；`ScanTaskService` 无分页 list 查询；`FindingLifecycleService` 有 `findingsByProject`（内存过滤）+ `findAll()` 可用；`RemediationService.list` 已确立「服务方法 + 内存过滤 + subList 分页」的 MVP 模式。

### 2.2 端点与视图（spec §6.2 字面）

| 端点 | 说明 | 返回 |
|---|---|---|
| `GET /api/v1/admin/dashboard` | 项目/任务/finding 计数 + 严重级分布 | `AdminDashboardView(projectCount, scanTaskCount, findingCount, severityDistribution: Map<String, Int>)` |
| `GET /api/v1/admin/scans?projectId=&engine=&status=&page=&size=` | 任务分页 + 过滤 | `PageView<ScanTaskView>` |
| `GET /api/v1/admin/findings?projectId=&status=&severity=&page=&size=` | 全局 finding 分页 | `PageView<FindingView>` |

- `PageView<T>`（items / page / size / total）—— 放 `module-common`（跨模块共享的 value type）。
- `AdminDashboardView` 放 `module-admin`。
- 分页语义与 `RemediationService.list` 一致：`from = page*size`，`subList(from, minOf(from+size, size))`；MVP 内存级分页（spec 未要求 DB 级）。

### 2.3 模块边界与数据来源（P2-D5：只依赖接口/value type，绝不 @Entity 跨模块）

`module-admin` 新增依赖 `module-project`、`module-scan`、`module-result`。取数一律经各模块暴露的 **port 接口 / 查询方法**（value type 进出），杜绝跨模块 @Entity：

- **projectCount**：module-project 新增 `ProjectQueryPort { fun count(): Long }` ——现 `ProjectService.list()` 返回 @Entity 不可直接暴露，须加 DTO 出口。最小实现：`ProjectService : ProjectQueryPort`，`count()` 返回 `projectRepository.count()`。
- **scanTaskCount + 任务列表**：module-scan 新增 `ScanTaskQueryPort { fun list(projectId: Long?, engine: String?, status: ScanTaskStatus?): List<ScanTaskView> }`（**不过滤分页**——分页/计数由 admin 侧按 `RemediationService.list` 模式切片）。`ScanTaskService` 实现（`scanTaskRepository.findAll()` + 过滤 + 按 id 倒序）。`ScanTaskView` 已有（`triggerScan` 返回）——复用其字段与映射。
- **findingCount + 严重级分布 + 全局 finding**：module-result 的 `FindingLifecyclePort` 扩展 `fun findingsGlobal(projectId: Long?, status: FindingStatus?, severity: String?): List<FindingView>`（`findAll()` + 过滤，同样不过滤分页）。`FindingView` 已有（含 severity）——count/severity 分布/分页全由 admin 从该列表聚合（MVP 与 `RemediationService.list` 口径一致）。

> 每个 query port 的精确签名在 plan 阶段钉死；本 spec 只定边界与返回类型。**「少建端口」优先**：能用既有方法（`ProjectService.list()` 之外的 `count()`）就不加抽象。**R-10.5-a（plan 细化）**：端口返回未分页过滤列表，分页/计数由 admin 侧切片 —— 与 `RemediationService.list` MVP 模式完全一致，避免每端口重复分页逻辑。

### 2.4 测试

- 集成测试前缀 **`ADM-*`**（spec §7），数据全局唯一。
- HTTP 级（`@SpringBootTest`，共享 Testcontainers 容器；MOCK env 下用 MockMvc，Ruling #49：**必须 `@WithMockUser(roles=["ADMIN"])`** 走真实安全链）。
- 覆盖：ADMIN 可访问三端点且数据正确；非 ADMIN（如 `roles=["DEVELOPER"]`）→ 403（`@PreAuthorize` 或 security chain 生效证明）。
- 单元：各 query port 实现（过滤/分页边界：空页、越界 from、severity 大小写）。

---

## 3. I4 — NotificationSender 契约 + 事件接线（spec §6.4）

### 3.1 契约（spec §6.4 逐字）

```kotlin
interface NotificationSender {
    fun send(channel: Channel, subject: String, body: String, recipients: List<Long>)
}
```

- **`Channel`**（module-notification/domain）：`enum class Channel { IN_APP, EMAIL, WECHAT, DINGTALK }`。MVP 实际发送端仅 `IN_APP`（落库 = 站内信表预留）；`EMAIL/WECHAT/DINGTALK` 为契约预留，发送时仅日志（渠道仍延后，spec §6.4）。
- **`NotificationService`** 提供 `NotificationSender` 的落库实现（`@Service`）：`send(channel, subject, body, recipients)` 对**每个 recipient 落一条** `Notification` 行（`channel = channel.name`，`type = "EVENT"`，`title = subject`，`content = body`，沿用 9.7 桩语义 `PENDING → SENT` + `sentAt`）。保留 `list(recipient)` 查询。**移除旧签名** `send(String, String, String, String, String)`（无调用方，9.7 Minor ② 已记）并同步更新 `NotificationServiceTest`。
- **`LogNotificationSender`**（spec §6.4 点名）：`@Service` 占位适配器，**`@Primary`**，实现 `NotificationSender`：`log.info` 记录 channel/subject/recipients（「写日志」占位）+ **委托 `NotificationService` 落库**（「站内信表预留」）。事件消费方与外部注入点统一按 `NotificationSender` 注入，实际拿到 `@Primary` 的 `LogNotificationSender`。
- `NotificationServiceTest`（9.7 已有）适配新签名：verify 两次 save（PENDING→SENT）逐 recipient。

### 3.2 事件模型（用户已确认：Spring ApplicationEvent）

- 事件类放 **`module-common`** 的 `com.example.compliance.common.event` 包（发布方与消费方都只依赖 common，零新增跨模块编译依赖边）。
- 两个事件：
  - **`FindingRegressionEvent`**（module-result 发布）：`data class FindingRegressionEvent(val projectId: Long, val scanTaskId: Long, val findingIds: List<Long>)` —— `FindingLifecycleService.verifyRechecking` 中 `regressed > 0` 时发布（回归检测事件）。
  - **`RemediationWaiverEvent`**（module-remediation 发布）：`data class RemediationWaiverEvent(val projectId: Long, val findingId: Long, val actorId: Long, val reason: String)` —— `RemediationService.status(...)` 中 `to == WAIVED` 时发布（豁免事件，spec §4.2 WAIVED 终态）。
- 消费方：**`NotificationEventListener`**（module-notification，`@Component` + `@EventListener` 方法）：
  - 收 `FindingRegressionEvent` → `notificationSender.send(IN_APP, "finding regressed", "回归：${findingIds.size} 个 finding 在扫描 $scanTaskId 复现", emptyList())`
  - 收 `RemediationWaiverEvent` → `notificationSender.send(IN_APP, "finding waived", "finding $findingId 被豁免（$reason）", listOf(event.actorId))`
- **best-effort**：监听器方法体内 `runCatching { ... }.onFailure { log.warn(...) }` —— 发送失败（含落库异常）仅记日志，**绝不向发布方事务传播**（spec §6.4「失败不影响主流程」）。`@EventListener` 默认同步即可，MVP 不引入 `@Async`。

> recipients 来源说明（MVP 简化）：豁免事件带自然接收人（actorId）；回归事件无明确 owner 字段 → `emptyList()`（零落库行，仅日志占位）。站点内信「接收人解析」留待后续。

### 3.3 模块依赖变化

**零新增跨模块依赖边。** 事件类放 `module-common`，发布方与监听方都只引用 common 类型：

- 发布方（`module-result` 的 verifyRechecking、`module-remediation` 的 status）：注入 Spring 的 `ApplicationEventPublisher`（Spring-context，随 spring-boot-starter 已在所有模块 classpath），`publishEvent(common.event.FindingRegressionEvent / RemediationWaiverEvent)` —— 不依赖 notification，不新增依赖边。
- 监听方（`module-notification` 的 `NotificationEventListener`）：方法签名引用 common 事件类型 → 仅依赖 module-common（已有），**不新增** module-result / module-remediation 依赖。

### 3.4 测试

- 单元：`NotificationServiceTest` 适配（新签名）；`LogNotificationSenderTest`（委托 + 日志）。
- 集成：**发布→监听→落库** 全链路（`@SpringBootTest` 内 `ApplicationEventPublisher.publishEvent(...)` → assert Notification 行落库）；**best-effort**：监听器失败（stub 抛异常）→ 发布方主流程事务不受影响（assert 无异常传播）。
- 数据前缀 `NTF-*`（或并入 ADM-* 测试面，按 plan 定）。

---

## 4. I8 — verifyRechecking 按 requestId 精确作用域硬化

### 4.1 现状与根因（ledger I8 裁决）

`FindingLifecycleService.verifyRechecking(projectId, scanTaskId, presentFindingIds)`（`FindingLifecycleService.kt:58-72`）遍历**项目全部 RECHECKING finding**（spec §4.3 字面）。复扫任务在 `ScanOrchestrator` 成功路径无条件调用（`ScanOrchestrator.kt:120`）。并发多复扫（或复扫与同步 status 并发）时，对同一 `@Version` 锁定的 `Finding` 行写 → `ObjectOptimisticLockingFailureException` → 500；且项目级遍历会把**别的**复扫任务的目标 finding 也一起验证（误 CLOSED 风险）。MVP 单复扫流程不可达，故 phase-2 park；M10 收敛为「按 requestId 精确作用域」。

### 4.2 硬化设计

- 复扫任务已携带锚点：`requestRecheck` 创建 ScanTask 时 `requestId = "recheck-f$findingId"`（`RemediationService.kt:155`）。
- **编排器**（`ScanOrchestrator.executeAsync`）：从 `task.requestId` 解析目标集 —— `requestId?.startsWith("recheck-f")` → `removePrefix("recheck-f").toLongOrNull()` → `targetFindingIds = setOf(findingId)`；否则 `emptySet()`。
- **`verifyRechecking` 签名变化**（`FindingLifecyclePort` + 实现 + 调用点同步）：
  ```kotlin
  fun verifyRechecking(projectId: Long, scanTaskId: Long, presentFindingIds: Set<Long>, targetFindingIds: Set<Long>): VerifyResult
  ```
  语义：只验证 `status == RECHECKING && id in targetFindingIds` 的 finding（absent → CLOSED / present → CONFIRMED，均 `transition` 走 P2-D4）。`targetFindingIds` 为空 → 不验证任何 finding（**行为变化**：非复扫扫描不再顺带验证其他 recheck 任务 —— 这正是硬化目的）。
- **影响面**：`FindingLifecyclePort`、`FindingLifecycleService`、`ScanOrchestrator`、`FindingLifecycleServiceTest`、`M7RemediationIntegrationTest`（复扫闭环：requestId=recheck-fX → 目标=X → absent→CLOSED / present→CONFIRMED 仍绿）、`M9RecheckFailureCompensationTest`（requestId 解析路径不变）、`M9RbacIntegrationTest`（F9 recheck 路径）。
- **裁决记录**：本变更是对 spec §4.3 字面（项目级）的**硬化偏离**，写入 ledger：单复扫行为零变化；多复扫消除误 CLOSED 与乐观锁冲突；成本若错 = spec 字面读者预期项目级（文档注释标注）。

### 4.3 测试

- 单测（`FindingLifecycleServiceTest` 扩展）：目标集外的 RECHECKING finding **不被**转移；目标集内 absent→CLOSED / present→CONFIRMED。
- 集成回归：M7 复扫闭环、M9 recheck 补偿、M9 RBAC recheck 路径保持绿。
- 并发硬化证明：新增测试模拟「两复扫任务各持不同 targetFindingIds」→ 互不干扰（避免直接并发断言，用目标集隔离语义验证代替）。

---

## 5. 清理项

### 5.1 清理① — `RuleQueryService.findByRuleCode` 改 JPQL（§6.5 I3 全量闭合）

- 现状（`RuleQueryService.kt:30-31`）：`ruleRepository.findAll().firstOrNull { it.ruleCode == ruleCode && it.status == PUBLISHED }` —— 每扫描逐 finding 调用的内存过滤残项（F7 已修 `publishedRuleByEngineRuleId`，此方法被 final-review 记为 I3 残项）。
- 修复：`RuleDefinitionRepository`（`repos.kt`）新增派生查询 `findFirstByRuleCodeAndStatus(ruleCode: String, status: RuleStatus): RuleDefinition?`（`order by r.id limit 1` 语义保留 firstOrNull 行为；`ruleCode` 非唯一则取 id 最小），`findByRuleCode` 委托之。与 F7 的 `findFirstByEngineAndEngineRuleIdAndStatus` 同模式。
- 测试：单测 verify `findAll` 不被调用（MockK `verify(exactly=0)`）+ 行为等价（PUBLISHED 命中 / 非 PUBLISHED 不命中 / 多规则取最小 id）。

### 5.2 清理② — `AuthPrincipal.hasRole()` 接线（消除未用死代码）

- 现状：`AuthPrincipal.hasRole(role)`（module-common，F4 引入）无调用方。
- 修复（接线，而非删除 —— 为服务端授权判定铺路）：`RemediationController.markFixed` 的 ADMIN 覆写判定从「service 内 `"ROLE_ADMIN" in actorAuthorities`」改为 controller 侧用 `auth.principal as? AuthPrincipal` 解析，`markFixed(id, actorId, isAdmin = principal?.hasRole("ADMIN") ?: false, ...)`；service 签名相应调整（`Set<String>` → `isAdmin: Boolean`，或保留 Set 但 controller 以 hasRole 构造）。
  - 最小改动形态由 implementer 裁决，**约束：`AuthPrincipal.hasRole` 必须在 M10 结束时有真实调用方**（接线）——若不成立则退化为删除（YAGNI），plan 阶段定死。
- 测试：M9RbacIntegrationTest 已含「非受让人 DEV fixed → 403 / ADMIN override」→ 保持绿即证明接线语义未变。

---

## 6. 模块依赖变化汇总

| 模块 | 新增依赖 | 说明 |
|---|---|---|
| `module-admin` | `module-project`、`module-scan`、`module-result` | 三端点取数（P2-D5：port/查询方法，value type 进出） |
| `module-notification` | — | 监听器引用 common 事件类型（§3.3 修正：事件类在 common → **零新增依赖边**） |
| `module-common` | — | 新增 `common.event`（两事件类）+ `PageView<T>` |
| `module-scan` | — | 新增 `ScanTaskQueryPort` + 实现 |
| `module-result` | — | `FindingLifecyclePort` 扩展 `findingsGlobal`（未分页过滤列表，R-10.5-a）；verifyRechecking 签名变化 |
| `module-project` | — | 新增 `ProjectQueryPort`（count） |
| `module-remediation` | — | `status(WAIVED)` 发布事件；`markFixed` 签名调整（清理②） |
| `module-rule` | — | `repos.kt` 派生查询 + `RuleQueryService.findByRuleCode` 委托 |

**不进 M10（维持延后/park）**：PF-10（PREPARING/PARTIAL_SUCCESS/retry —— 多引擎接入时，spec §1.1）；m1-m12 各 adjudicated parks（`findingsByProject` 的 findAll 过滤、V9 DEFAULT 'OPEN' 等）；渠道（企微/钉钉）真实发送。

---

## 7. 测试与门禁

- 数据前缀：`ADM-*`（admin 集成）、`NTF-*`（通知事件）、既有 `REM-*`/`M9-*` 回归。
- 共享 Testcontainers 容器、`SmokeFirstClassOrderer` 不变（spec §7 约束延续）。
- 每任务：RED → GREEN → 该模块单测 → `./gradlew build` 全量回归 → commit（未 push，Ruling #60）。
- M10 完成标准：§1 验收 5 项全落地 + 全量 build 绿 + phase-2 既有集成套件（30 个 app-server 测试）零回归。
