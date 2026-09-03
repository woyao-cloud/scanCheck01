# M11 多引擎集成实施计划 — Gitleaks + Trivy

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 接入 Gitleaks（密钥扫描，代码类）与 Trivy（依赖漏洞，依赖类）两个引擎，首次落地依赖类字段与依赖类指纹，打通「代码类 + 依赖类」双类 finding 的统一扫描链。

**Architecture:** 五任务按依赖序推进。11.1 契约扩展（module-result：`RawFinding` +5 依赖字段末尾默认值 + `FingerprintGenerator.generateDependency`）；11.2 依赖类落地（module-result：`NewFinding` +5 字段、`Finding` 实体 +5 列、`FindingView`/`toView` 扩展、`FindingService.upsertByFingerprint` 依赖类分流 + app-server V12 迁移）；11.3 GitleaksAdapter（module-engine-adapter，四类 + fixtures + 三测试类）；11.4 TrivyAdapter（同构）；11.5 编排器接线 + 配置（module-scan `NewFinding` 透传、`application.yml` checkout-engines + 新配置段、M11 集成测试 STUBG/STUBT + 配置断言）。

**Tech Stack:** Kotlin 2.0.21 / Spring Boot 3.3.5 / Spring Data JPA / Flyway / PostgreSQL 16（Testcontainers）/ JUnit 5 + MockK / Jackson。全部 Gradle 命令用 `./gradlew`（wrapper 8.8）。

**Spec:**
- `docs/superpowers/specs/2026-09-03-code-compliance-platform-m11-design.md`（本里程碑设计，用户 2026-09-03 确认；§3-§7 为任务权威来源）
- `plan.md`（§7.3 指纹规范 / 「新增扫描引擎」指引 / 统一 Finding 模型）

## Global Constraints

以下约束对每个任务隐式生效：

1. **Gradle**：一律 `./gradlew`（wrapper 8.8），绝不使用系统 `gradle`。
2. **模块依赖（P2-D5）**：跨模块一律通过**接口/端口 + 值类型（DTO）**，**绝不 import `@Entity`**。`RawFinding`/`NewFinding`/`FindingView` 是 module-result 的**契约值类型**（module-scan / module-engine-adapter 消费，既有边界不变）。`module-engine-adapter` 只依赖 common + result。
3. **状态权威（P2-D4）**：`finding.status` 唯一权威；一切转移经 `FindingLifecyclePort.transition`；依赖类 finding 复用**同一**状态机与去重语义（ACTIVE 保持 / WAIVED 跳过 / FIXED·CLOSED 回归 CONFIRMED）。
4. **Ruling #45/#52**：编排器路径不添加 `@Transactional`；各 repo save 自提交；`transition` REQUIRED 自提交。
5. **MockK strict**：非 relaxed mock 未 stub 即调 → `MockKException`；`every` 必须精确覆盖（`Unit` 返回值 stub 需 `just Runs`，import `io.mockk.Runs`）。
6. **共享 Testcontainers**：app-server 集成测试共享一个 PostgreSQL 容器；数据全局唯一（本里程碑前缀 **`M11-*`**）；`SmokeFirstClassOrderer` 不变。
7. **SDD 纪律**：subagent 不得再派 subagent；实现者**串行**派发（不并行）；每任务 RED→GREEN→全量 `./gradlew build` 回归→commit（**不 push**，推送需用户显式授权）。
8. **兼容性红线**：`RawFinding`/`NewFinding`/`FindingView` 的新字段**全部追加在参数末尾且带默认值**——既有位置参数调用点（M6/M7/M8/M9/ScanPipeline/M9Rbac/Report 各测试的 `RawFinding(...)` 7-8 参、`FindingServiceTest` 的 `NewFinding(...)` 8 参、`toView` 的 12 参）**编译零破坏**。
9. **统一 severity**：引擎原生等级必须经 `XxxSeverityMapper` 映射为平台等级（CRITICAL/HIGH/MEDIUM/LOW/INFO）后才进业务层；不允许引擎自定义等级直达 Finding 行。
10. **不进 M11**：真实二进制 E2E（gitleaks/trivy 本机扫描）、质量门禁、规则种子数据预置（集成测试自建规则，镜像 M8）、其他引擎、原始结果落对象存储。

---

## 文件结构总览

| 模块 | 新建/修改 | 职责 |
|---|---|---|
| module-result | `engine/ScanEngineAdapter.kt`（`RawFinding` +5 字段）、`infrastructure/FingerprintGenerator.kt`（+`generateDependency`）、`application/FindingService.kt`（`NewFinding` +5 字段 + 分流）、`domain/Finding.kt`（+5 字段）、`application/FindingLifecyclePort.kt`（`FindingView` +5 字段）、`application/FindingLifecycleService.kt`（`toView` 映射） | 契约 + 指纹 + 依赖类落地 |
| module-engine-adapter | `engineadapter/gitleaks/`（新，4 类）、`engineadapter/trivy/`（新，4 类）、`src/test/resources/gitleaks/basic.json`（新）、`src/test/resources/trivy/basic.json`（新） | 双引擎适配器 |
| module-scan | `application/ScanOrchestrator.kt`（`NewFinding` 透传 5 字段） | 编排器接线 |
| app-server | `src/main/resources/db/migration/V12__finding_dependency_fields.sql`（新）、`src/main/resources/application.yml`（+配置）、`src/test/kotlin/com/example/compliance/scan/M11EngineIntegrationTest.kt`（新） | 迁移 + 配置 + 集成测试 |
| module-result test | `infrastructure/FingerprintGeneratorTest.kt`（+`generateDependency` 测试）、`application/FindingServiceTest.kt`（+依赖类测试） | 单测 |

---

## Task 11.1: 契约扩展 — RawFinding 依赖字段 + 依赖指纹（module-result）

**Files:**
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/engine/ScanEngineAdapter.kt`（`RawFinding` +5 可空字段，末尾默认值）
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/infrastructure/FingerprintGenerator.kt`（+`generateDependency`）
- Modify: `module-result/src/test/kotlin/com/example/compliance/result/infrastructure/FingerprintGeneratorTest.kt`（+3 测试）

**Interfaces:**
- Produces:
  - `RawFinding(engineRuleId, ruleName=null, filePath, line=null, severity, message=null, codeSnippet=null, category=null, packageName=null, packageVersion=null, fixedVersion=null, cveId=null, cvssScore=null)`（末尾 5 参可空+默认值 → 全部既有 7-8 参位置调用点零破坏）
  - `FingerprintGenerator.generateDependency(projectId: Long, packageName: String, packageVersion: String?, cveId: String): String`
- Consumes: 无（本任务隔离；11.2 的 FindingService 分流消费 `generateDependency`）。

> **为何先于 11.3/11.4**：Gitleaks/Trivy 适配器的 `RawFinding(...)` 构造依赖新字段；`generateDependency` 是本里程碑唯一结构性新方法。

- [ ] **Step 1: 写失败测试**（`FingerprintGeneratorTest.kt` 追加）

```kotlin
@Test
fun `generateDependency is deterministic and differs from code fingerprint`() {
    val a = generator.generateDependency(9L, "lodash", "4.17.20", "CVE-2024-1234")
    val b = generator.generateDependency(9L, "lodash", "4.17.20", "CVE-2024-1234")
    val code = generator.generate(9L, "M11TRV", "package-lock.json", null, null)
    assertEquals(a, b)                    // 确定性
    assertNotEquals(a, code)              // 与代码类指纹不冲突（uq_finding_fp 唯一索引）
}

@Test
fun `generateDependency distinguishes package version and cve`() {
    val v1 = generator.generateDependency(9L, "lodash", "4.17.20", "CVE-2024-1234")
    val v2 = generator.generateDependency(9L, "lodash", "4.17.21", "CVE-2024-1234")
    val v3 = generator.generateDependency(9L, "lodash", "4.17.20", "CVE-2024-5678")
    assertNotEquals(v1, v2)               // 版本参与指纹
    assertNotEquals(v1, v3)               // CVE 参与指纹
}

@Test
fun `generateDependency tolerates null version`() {
    val a = generator.generateDependency(9L, "lodash", null, "CVE-2024-1234")
    val b = generator.generateDependency(9L, "lodash", null, "CVE-2024-1234")
    assertEquals(a, b)
}
```

> 参考现有 `FingerprintGeneratorTest` 的构造方式（`FingerprintGenerator` 无依赖，直接 `val generator = FingerprintGenerator()`；import `kotlin.test.assertNotEquals`）。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-result:test --tests "*FingerprintGeneratorTest*"`
Expected: FAIL —— `Unresolved reference 'generateDependency'`（编译失败即 RED）。

- [ ] **Step 3: 实现**

`FingerprintGenerator.kt` 追加：

```kotlin
/** 依赖类指纹（plan.md §7.3 字面）：sha256(projectId|packageName|packageVersion|cveId)。
 *  M11：Trivy 依赖漏洞首次落地 —— 与代码类指纹输入不同，哈希空间不冲突。 */
fun generateDependency(projectId: Long, packageName: String, packageVersion: String?, cveId: String): String {
    val normalized = listOf(
        projectId.toString(),
        packageName,
        packageVersion ?: "",
        cveId,
    ).joinToString("|")
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalized.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
```

`ScanEngineAdapter.kt` 的 `RawFinding` 末尾追加 5 个可空字段（带默认值）：

```kotlin
/** 引擎原生结果，severity 已归一化为 LOW/MEDIUM/HIGH/CRITICAL。 */
data class RawFinding(
    val engineRuleId: String,
    val ruleName: String? = null,
    val filePath: String,
    val line: Int? = null,
    val severity: String,
    val message: String? = null,
    val codeSnippet: String? = null,
    val category: String? = null,
    // M11 依赖类字段（Trivy 使用；代码类引擎恒为 null）。追加在末尾带默认值 → 既有位置调用点零破坏。
    val packageName: String? = null,
    val packageVersion: String? = null,
    val fixedVersion: String? = null,
    val cveId: String? = null,
    val cvssScore: Double? = null,
)
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :module-result:test --tests "*FingerprintGeneratorTest*"`
Expected: PASS（新旧测试全绿，新增 3 测试过）。

- [ ] **Step 5: 全量回归 + 提交**

Run: `./gradlew build` — Expected: BUILD SUCCESSFUL。
Commit（staged 仅 module-result/src/main + module-result/src/test）：
`feat(result): RawFinding dependency fields + FingerprintGenerator.generateDependency (m11)`

---

## Task 11.2: 依赖类落地 — NewFinding/Finding/FindingView + V12 + 分流（module-result + app-server）

**Files:**
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingService.kt`（`NewFinding` +5 字段末尾默认值；`upsertByFingerprint` 依赖类分流 + 落字段）
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/domain/Finding.kt`（+5 可空字段）
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecyclePort.kt`（`FindingView` +5 可空字段末尾默认值）
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingLifecycleService.kt:96-99`（`toView` 映射补 5 字段）
- Create: `app-server/src/main/resources/db/migration/V12__finding_dependency_fields.sql`
- Modify: `module-result/src/test/kotlin/com/example/compliance/result/application/FindingServiceTest.kt`（+3 依赖类测试）

**Interfaces:**
- Consumes: `FingerprintGenerator.generateDependency(projectId, packageName, packageVersion, cveId)`（Task 11.1）；`RawFinding` 的新字段在 Task 11.5 由编排器透传进 `NewFinding`。
- Produces:
  - `NewFinding(..., cvssScore=null)`（末尾 5 可空字段 + 默认值 → 既有 8 参位置调用点零破坏）
  - `Finding`（@Entity）+5 可空字段：`packageName/packageVersion/fixedVersion/cveId`（`String?`）+ `cvssScore: java.math.BigDecimal?`。**实体持 BigDecimal ↔ V12 列 NUMERIC**（先例：`checklist.score_weight`、`scan.score` 均 NUMERIC↔BigDecimal，`ddl-auto: validate` 下成立）；DTO 持 `Double?`，边界转换（`toBigDecimal()` / `toDouble()`）
  - `FindingView`（value type）+5 可空字段末尾默认值（`cvssScore: Double?`）
  - `upsertByFingerprint` 分流：`packageName != null || cveId != null` → 依赖指纹 + 落 5 字段；否则既有代码类路径

> **为何 V12 捆绑**：`Finding` 实体（module-result）与迁移（app-server）必须同一提交 —— `ddl-auto: validate` 下实体加列而迁移缺席 → 全量 build 的 app-server 集成测试启动即崩；迁移先加而实体未加同理。

- [ ] **Step 1: 写失败测试**（`FindingServiceTest.kt` 追加）

```kotlin
@Test
fun `dependency finding uses dependency fingerprint and persists dependency fields`() {
    val fpDep = "fp-dep"
    val dep = NewFinding("M11TRV", "lodash proto pollution", "package-lock.json", null, "CRITICAL", null, "pollution", null,
        "lodash", "4.17.20", "4.17.21", "CVE-2024-1234", 9.8)
    every { fingerprintGenerator.generateDependency(9L, "lodash", "4.17.20", "CVE-2024-1234") } returns fpDep
    every { findingRepository.findByProjectIdAndFingerprint(9L, fpDep) } returns null
    every { findingRepository.save(any<Finding>()) } answers { firstArg<Finding>().apply { id = 1L } }
    every { historyRepository.save(any<FindingHistory>()) } answers { firstArg<FindingHistory>() }

    val result = findingService.upsertByFingerprint(9L, 60L, "TRIVY", listOf(dep))

    assertEquals(UpsertResult(1, 0), result)
    verify { findingRepository.save(match {
        it.fingerprint == fpDep && it.packageName == "lodash" && it.cveId == "CVE-2024-1234" &&
        it.packageVersion == "4.17.20" && it.fixedVersion == "4.17.21" && it.cvssScore == 9.8.toBigDecimal()
    }) }
    verify { historyRepository.save(match { it.action == "CREATED" && it.scanTaskId == 60L }) }
}

@Test
fun `reappearing dependency finding increments occurrence and keeps active state`() {
    val fpDep = "fp-dep-re"
    val existing = Finding().apply { id = 7L; projectId = 9L; status = FindingStatus.NEW; fingerprint = fpDep; occurrenceCount = 1 }
    every { fingerprintGenerator.generateDependency(9L, "lodash", "4.17.20", "CVE-2024-1234") } returns fpDep
    every { findingRepository.findByProjectIdAndFingerprint(9L, fpDep) } returns existing
    every { findingRepository.save(any<Finding>()) } answers { firstArg() }
    every { historyRepository.save(any<FindingHistory>()) } answers { firstArg<FindingHistory>() }

    val dep = NewFinding("M11TRV", "lodash proto pollution", "package-lock.json", null, "CRITICAL", null, "pollution", null,
        "lodash", "4.17.20", "4.17.21", "CVE-2024-1234", 9.8)
    val result = findingService.upsertByFingerprint(9L, 61L, "TRIVY", listOf(dep))

    assertEquals(UpsertResult(0, 1), result)
    assertEquals(FindingStatus.NEW, existing.status)   // 活动集 → 状态保持
    assertEquals(2, existing.occurrenceCount)
    verify { historyRepository.save(match { it.action == "REAPPEARED" && it.scanTaskId == 61L }) }
}

@Test
fun `code finding path is unchanged`() {
    val fp = "fp-code"
    every { fingerprintGenerator.generate(9L, "R1", "A.java", 1, "s") } returns fp
    every { findingRepository.findByProjectIdAndFingerprint(9L, fp) } returns null
    every { findingRepository.save(any<Finding>()) } answers { firstArg<Finding>().apply { id = 1L } }
    every { historyRepository.save(any<FindingHistory>()) } answers { firstArg<FindingHistory>() }

    findingService.upsertByFingerprint(9L, 62L, "STUB", listOf(newFinding))

    verify { findingRepository.save(match { it.fingerprint == fp && it.packageName == null && it.cveId == null }) }
    verify(exactly = 0) { fingerprintGenerator.generateDependency(any(), any(), any(), any()) }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-result:test --tests "*FindingServiceTest*"`
Expected: FAIL —— 依赖类测试红（`FindingService` 尚未调用 `generateDependency`，`Finding` 实体无 `packageName` 字段编译失败亦算 RED）。

- [ ] **Step 3: 实现**

`FindingService.kt` 的 `NewFinding` 末尾追加（与 `RawFinding` 同序同型）：

```kotlin
data class NewFinding(
    val ruleCode: String,
    val ruleName: String?,
    val filePath: String,
    val lineNumber: Int?,
    val severity: String,
    val category: String?,
    val message: String?,
    val codeSnippet: String?,
    // M11 依赖类字段（Trivy 使用；代码类恒 null）。末尾默认值 → 既有 8 参位置调用点零破坏。
    val packageName: String? = null,
    val packageVersion: String? = null,
    val fixedVersion: String? = null,
    val cveId: String? = null,
    val cvssScore: Double? = null,
)
```

`Finding.kt`（`@Entity`）末尾追加 5 个可空字段：

```kotlin
    // M11 依赖类字段（Trivy 使用；代码类恒 null）
    var packageName: String? = null
    var packageVersion: String? = null
    var fixedVersion: String? = null
    var cveId: String? = null
    // cvssScore：实体持 BigDecimal ↔ V12 列 NUMERIC（先例 checklist.score_weight / scan.score）；
    // DTO（RawFinding/NewFinding/FindingView）持 Double，边界转换 toBigDecimal / toDouble。
    var cvssScore: java.math.BigDecimal? = null
```

`FindingService.upsertByFingerprint` 改为分流（fingerprint 计算 + 创建行落字段）：

```kotlin
        for (f in findings) {
            // M11：依赖类（packageName/cveId 非空）走依赖指纹（plan.md §7.3）；否则代码类既有指纹
            val fingerprint = if (f.packageName != null || f.cveId != null)
                fingerprintGenerator.generateDependency(projectId, f.packageName!!, f.packageVersion, f.cveId!!)
            else
                fingerprintGenerator.generate(projectId, f.ruleCode, f.filePath, f.lineNumber, f.codeSnippet)
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
                    packageName = f.packageName        // M11 依赖字段
                    packageVersion = f.packageVersion
                    fixedVersion = f.fixedVersion
                    cveId = f.cveId
                    cvssScore = f.cvssScore?.toBigDecimal()   // Double → BigDecimal（实体持 NUMERIC 列）
                    this.fingerprint = fingerprint
                }).let { saved ->
                    historyRepository.save(FindingHistory().apply {
                        findingId = saved.id!!; this.scanTaskId = scanTaskId; action = "CREATED"
                    })
                }
                created++
            } else {
                // ... 其余（REAPPEARED + 状态机处置）保持不动
            }
        }
```

`FindingLifecyclePort.kt` 的 `FindingView` 末尾追加 5 个可空字段（默认值）：

```kotlin
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
    val engine: String = "",
    val packageName: String? = null,      // M11 依赖字段
    val packageVersion: String? = null,
    val fixedVersion: String? = null,
    val cveId: String? = null,
    val cvssScore: Double? = null,
)
```

`FindingLifecycleService.toView` 补 5 字段：

```kotlin
    private fun com.example.compliance.result.domain.Finding.toView() = FindingView(
        id!!, projectId, scanTaskId, ruleCode, severity, status, filePath, lineNumber,
        firstSeenAt, lastSeenAt, occurrenceCount, engine,
        packageName, packageVersion, fixedVersion, cveId, cvssScore?.toDouble(),
    )
```

`V12__finding_dependency_fields.sql`：

```sql
-- M11：依赖类字段（Trivy 依赖漏洞）。全部可空 —— 既有 finding 行不迁移、不填默认值。
-- cvss_score NUMERIC ↔ Finding.cvssScore BigDecimal（先例：checklist.score_weight、scan.score 均 NUMERIC↔BigDecimal）。
ALTER TABLE finding
    ADD COLUMN package_name    TEXT,
    ADD COLUMN package_version TEXT,
    ADD COLUMN fixed_version   TEXT,
    ADD COLUMN cve_id          VARCHAR(64),
    ADD COLUMN cvss_score      NUMERIC;
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :module-result:test --tests "*FindingServiceTest*"` — Expected: PASS。
Run: `./gradlew :app-server:test --tests "*SmokeIntegrationTest*"` — Expected: PASS（验证 V12 迁移 + `ddl-auto: validate` 实体/列一致）。

- [ ] **Step 5: 全量回归 + 提交**

Run: `./gradlew build` — Expected: BUILD SUCCESSFUL（全部既有 app-server 集成测试在 V12 下零回归）。
Commit（staged 仅 module-result/src/main + module-result/src/test + app-server/src/main/resources/db/migration）：
`feat(result): dependency finding fields + V12 migration + fingerprint dispatch in upsert (m11)`

---

## Task 11.3: GitleaksAdapter（module-engine-adapter）

**Files:**
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/gitleaks/GitleaksCli.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/gitleaks/GitleaksResultParser.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/gitleaks/GitleaksSeverityMapper.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/gitleaks/GitleaksAdapter.kt`
- Create: `module-engine-adapter/src/test/resources/gitleaks/basic.json`
- Create: `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/gitleaks/GitleaksResultParserTest.kt`
- Create: `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/gitleaks/GitleaksSeverityMapperTest.kt`
- Create: `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/gitleaks/GitleaksAdapterTest.kt`

**Interfaces:**
- Consumes: `RawFinding`（Task 11.1 形态，含依赖字段末尾默认值）；`ScanContext`（`workDir`/`repoUrl`）；`ScanExecutionResult`（`success/errorMessage/durationMs/stdoutRef`）——均来自 module-result/engine。
- Produces: `GitleaksAdapter : ScanEngineAdapter`（`engine = "GITLEAKS"`），五方法镜像 `SemgrepAdapter`。

> **为何与 Trivy 分开**：两者 parser 格式不同（gitleaks 顶层数组 vs trivy Results[].Vulnerabilities[]）、fixture 不同、测试面独立 —— 各自需要独立审查表面（writing-plans：one-dispatch-per-task for its own review surface）。

- [ ] **Step 1: 写 fixture + 失败测试**

`gitleaks/basic.json`（顶层数组；第 2 条 Severity 为空串 → 验证缺省映射路径）：

```json
[
  {
    "RuleID": "generic-api-key",
    "Description": "Found a generic API key",
    "StartLine": 12,
    "EndLine": 12,
    "StartColumn": 18,
    "EndColumn": 50,
    "Match": "apiKey = \"sk-1234567890abcdef\"",
    "Secret": "sk-1234567890abcdef",
    "File": "src/main/resources/application.yml",
    "Commit": "",
    "Entropy": 3.5,
    "Author": "",
    "Email": "",
    "Date": "",
    "Message": "",
    "Severity": "HIGH"
  },
  {
    "RuleID": "aws-access-token",
    "Description": "AWS Access Token",
    "StartLine": 5,
    "EndLine": 5,
    "StartColumn": 10,
    "EndColumn": 30,
    "Match": "AKIAIOSFODNN7EXAMPLE",
    "Secret": "AKIAIOSFODNN7EXAMPLE",
    "File": ".env",
    "Commit": "",
    "Entropy": 2.8,
    "Author": "",
    "Email": "",
    "Date": "",
    "Message": "",
    "Severity": ""
  }
]
```

`GitleaksResultParserTest.kt`：

```kotlin
package com.example.compliance.engineadapter.gitleaks

import com.example.compliance.result.engine.RawFinding
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitleaksResultParserTest {
    private val parser = GitleaksResultParser()
    private val json = javaClass.getResource("/gitleaks/basic.json").readText(StandardCharsets.UTF_8)

    @Test
    fun `parses leaks into raw findings with native severity`() {
        val findings = parser.parse(json)
        assertEquals(2, findings.size)
        assertEquals("generic-api-key", findings[0].engineRuleId)
        assertEquals("src/main/resources/application.yml", findings[0].filePath)
        assertEquals(12, findings[0].line)
        assertEquals("HIGH", findings[0].severity)                     // 保留原生 severity
        assertEquals("apiKey = \"sk-1234567890abcdef\"", findings[0].codeSnippet)
        assertEquals("aws-access-token", findings[1].engineRuleId)
        assertEquals("", findings[1].severity)                          // 空串原样保留 → normalize 缺省映射
        assertTrue(findings[1].packageName == null)                     // 代码类无依赖字段
    }

    @Test
    fun `empty report yields empty list`() {
        assertTrue(parser.parse("[]").isEmpty())
    }

    @Test
    fun `non-array or invalid json yields empty list`() {
        assertTrue(parser.parse("{}").isEmpty())
        assertTrue(parser.parse("not json").isEmpty())
    }
}
```

`GitleaksSeverityMapperTest.kt`：

```kotlin
package com.example.compliance.engineadapter.gitleaks

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class GitleaksSeverityMapperTest {
    private val mapper = GitleaksSeverityMapper()

    @Test
    fun `passes through HIGH MEDIUM LOW and defaults to MEDIUM`() {
        assertEquals("HIGH", mapper.map("HIGH"))
        assertEquals("MEDIUM", mapper.map("MEDIUM"))
        assertEquals("LOW", mapper.map("LOW"))
        assertEquals("MEDIUM", mapper.map(""))       // 旧版无 Severity 字段 / 空串
        assertEquals("MEDIUM", mapper.map("CRITICAL")) // 超出等级兜底（不直接进业务层）
    }
}
```

`GitleaksAdapterTest.kt`（镜像 `SemgrepAdapterTest` 形态）：

```kotlin
package com.example.compliance.engineadapter.gitleaks

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitleaksAdapterTest {
    private val cli = mockk<GitleaksCli>()
    private val adapter = GitleaksAdapter(cli, GitleaksResultParser(), GitleaksSeverityMapper())

    private val json = javaClass.getResource("/gitleaks/basic.json").readText(StandardCharsets.UTF_8)
    private val ctx = ScanContext(1L, 1L, "https://git.example.com/repo.git", "main")

    @Test
    fun `execute and collect keep raw severities, normalize maps them`() {
        every { cli.run(any()) } returns json
        val execution = adapter.executeScan(ctx)
        assertTrue(execution.success)
        assertTrue(execution.stdoutRef != null)
        val raw = adapter.collectResult(ctx)
        assertEquals(2, raw.size)
        assertEquals("HIGH", raw[0].severity)
        assertEquals("", raw[1].severity)
        val normalized = adapter.normalizeResult(ctx, raw)
        assertEquals("HIGH", normalized[0].severity)
        assertEquals("MEDIUM", normalized[1].severity)   // 空串 → 缺省 MEDIUM
    }

    @Test
    fun `cli failure maps to unsuccessful execution without writing stdout file`() {
        every { cli.run(any()) } throws IllegalStateException("gitleaks exited with code 126")
        val execution = adapter.executeScan(ctx)
        assertTrue(!execution.success)
        assertEquals("gitleaks exited with code 126", execution.errorMessage)
        assertTrue(adapter.collectResult(ctx).isEmpty())
    }

    @Test
    fun `cleanup clears stdout file`() {
        every { cli.run(any()) } returns json
        adapter.executeScan(ctx)
        adapter.collectResult(ctx)
        adapter.cleanup(ctx)
        assertTrue(adapter.collectResult(ctx).isEmpty())
    }

    @Test
    fun `scans workdir when present and falls back to repo url`() {
        every { cli.run("/tmp/w") } returns "[]"
        adapter.executeScan(ScanContext(1L, 1L, "https://git.example.com/repo.git", "main", workDir = "/tmp/w"))
        verify { cli.run("/tmp/w") }

        every { cli.run("https://git.example.com/repo.git") } returns "[]"
        adapter.executeScan(ctx)
        verify { cli.run("https://git.example.com/repo.git") }
    }

    @Test
    fun `engine name is GITLEAKS`() {
        assertEquals("GITLEAKS", adapter.engine)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-engine-adapter:test --tests "*Gitleaks*"`
Expected: FAIL —— `Unresolved reference 'GitleaksAdapter'` 等（编译失败即 RED）。

- [ ] **Step 3: 实现**

`GitleaksCli.kt`：

```kotlin
package com.example.compliance.engineadapter.gitleaks

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.TimeUnit

interface GitleaksCli {
    fun run(targetPath: String): String
}

/** gitleaks CLI 进程封装（spec §5.1）：`gitleaks dir <target> --report-format json --report-path <file> --no-banner`。
 *  稳健设计（吸取 R-8.2-b 教训 + 避免 JSON 污染）：
 *    - JSON 报告经 --report-path 直接落盘，stdout/stderr 只承载日志 —— 不被合并进 JSON
 *    - stdout/stderr 各自重定向独立临时文件（redirectErrorStream=false + 双 redirect）→ 无未读管道，不假超时
 *    - exit 语义：0=无泄漏 / 1=有泄漏，均成功（报告已落盘）；其它退出码抛异常（同 F1：绝不产出假干净扫描）
 *    - 报告文件不存在（gitleaks 旧版无泄漏时不写）→ 返回 "[]"，parser 兼容 */
@Component
class ProcessGitleaksCli(
    @Value("\${app.gitleaks.timeout-seconds:300}")
    private val timeoutSeconds: Long,
) : GitleaksCli {
    override fun run(targetPath: String): String {
        val report = File.createTempFile("gitleaks-report-", ".json")
        val out = File.createTempFile("gitleaks-out-", ".log")
        val err = File.createTempFile("gitleaks-err-", ".log")
        try {
            val cmd = listOf("gitleaks", "dir", targetPath,
                "--report-format", "json", "--report-path", report.absolutePath, "--no-banner")
            val process = ProcessBuilder(cmd)
                .redirectOutput(out)
                .redirectError(err)
                .start()
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
                throw IllegalStateException("gitleaks timed out after ${timeoutSeconds}s")
            }
            val code = process.exitValue()
            if (code != 0 && code != 1) {
                throw IllegalStateException("gitleaks exited with code $code")
            }
            return if (report.exists()) report.readText() else "[]"
        } finally {
            report.delete(); out.delete(); err.delete()
        }
    }
}
```

`GitleaksResultParser.kt`：

```kotlin
package com.example.compliance.engineadapter.gitleaks

import com.example.compliance.result.engine.RawFinding
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/** gitleaks JSON 报告解析（spec §5.2）：顶层 leak 数组 → RawFinding（保留原生 severity，映射在 normalize）。 */
@Component
class GitleaksResultParser {
    private val objectMapper = ObjectMapper()

    fun parse(report: String): List<RawFinding> {
        val root = runCatching { objectMapper.readTree(report) }.getOrNull()
            ?: return emptyList()
        if (!root.isArray) return emptyList()
        return root.mapNotNull { node ->
            val ruleId = node.path("RuleID").asText("")
            val file = node.path("File").asText("")
            if (ruleId.isEmpty() || file.isEmpty()) return@mapNotNull null
            RawFinding(
                engineRuleId = ruleId,
                ruleName = null,
                filePath = file,
                line = node.path("StartLine").takeIf { !it.isMissingNode && it.canConvertToInt() }?.asInt(),
                severity = node.path("Severity").asText("MEDIUM"),
                message = node.path("Description").takeIf { !it.isMissingNode }?.asText(),
                codeSnippet = node.path("Match").takeIf { !it.isMissingNode && it.asText().isNotBlank() }?.asText()
                    ?: node.path("Secret").takeIf { !it.isMissingNode }?.asText(),
                category = null,
            )
        }
    }
}
```

`GitleaksSeverityMapper.kt`：

```kotlin
package com.example.compliance.engineadapter.gitleaks

import org.springframework.stereotype.Component

/** Gitleaks 原生 severity（HIGH/MEDIUM/LOW；旧版无 Severity 字段 → 缺省 MEDIUM）→ 统一等级。 */
@Component
class GitleaksSeverityMapper {
    fun map(engineSeverity: String): String = when (engineSeverity.uppercase()) {
        "HIGH" -> "HIGH"
        "MEDIUM" -> "MEDIUM"
        "LOW" -> "LOW"
        else -> "MEDIUM"
    }
}
```

`GitleaksAdapter.kt`：

```kotlin
package com.example.compliance.engineadapter.gitleaks

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanExecutionResult
import org.springframework.stereotype.Component
import java.io.File

/** Gitleaks 密钥扫描适配器（spec §5.4）：五方法镜像 SemgrepAdapter —— 无实例可变状态，stdout 文件按 scanTaskId 派生。 */
@Component
class GitleaksAdapter(
    private val cli: GitleaksCli,
    private val parser: GitleaksResultParser,
    private val severityMapper: GitleaksSeverityMapper,
) : ScanEngineAdapter {

    override val engine: String = "GITLEAKS"

    override fun prepareScan(context: ScanContext) {
        // 无前置动作：超时与临时文件重定向由 GitleaksCli 负责
    }

    override fun executeScan(context: ScanContext): ScanExecutionResult {
        val target = scanTarget(context)
        val start = System.currentTimeMillis()
        return runCatching {
            val report = cli.run(target)
            val file = stdoutFile(context)
            file.writeText(report)
            ScanExecutionResult(success = true, stdoutRef = file.absolutePath)
        }.getOrElse { e ->
            // cli 失败（exit 非 0/1 / 超时）→ success=false，不落盘 stdout（F1 同款：绝不产出假干净扫描）
            ScanExecutionResult(success = false, errorMessage = e.message, durationMs = System.currentTimeMillis() - start)
        }
    }

    override fun collectResult(context: ScanContext): List<RawFinding> {
        val file = stdoutFile(context)
        if (!file.exists()) return emptyList()
        return parser.parse(runCatching { file.readText() }.getOrDefault("[]"))
    }

    override fun normalizeResult(context: ScanContext, raw: List<RawFinding>): List<RawFinding> =
        raw.map { it.copy(severity = severityMapper.map(it.severity)) }

    override fun cleanup(context: ScanContext) {
        runCatching { stdoutFile(context).delete() }
    }

    private fun stdoutFile(context: ScanContext): File =
        File(System.getProperty("java.io.tmpdir"), "gitleaks-report-${context.scanTaskId}.json")

    private fun scanTarget(context: ScanContext): String = context.workDir ?: context.repoUrl
}
```

> 注：`app.gitleaks.command` 为文档镜像（镜像 `app.semgrep.command`，SemgrepCli 同样不消费）；CLI 硬编码二进制名，与 SemgrepCli 一致。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :module-engine-adapter:test --tests "*Gitleaks*"`
Expected: PASS（3 测试类全绿）。

- [ ] **Step 5: 全量回归 + 提交**

Run: `./gradlew build` — Expected: BUILD SUCCESSFUL。
Commit（staged 仅 module-engine-adapter/src/main + module-engine-adapter/src/test）：
`feat(engine-adapter): gitleaks adapter with cli/parser/severity-mapper and fixture tests (m11)`

---

## Task 11.4: TrivyAdapter（module-engine-adapter）

**Files:**
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/trivy/TrivyCli.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/trivy/TrivyResultParser.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/trivy/TrivySeverityMapper.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/trivy/TrivyAdapter.kt`
- Create: `module-engine-adapter/src/test/resources/trivy/basic.json`
- Create: `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/trivy/TrivyResultParserTest.kt`
- Create: `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/trivy/TrivySeverityMapperTest.kt`
- Create: `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/trivy/TrivyAdapterTest.kt`

**Interfaces:**
- Consumes: `RawFinding`（含依赖字段，Task 11.1）；`ScanContext`/`ScanExecutionResult`。
- Produces: `TrivyAdapter : ScanEngineAdapter`（`engine = "TRIVY"`）；依赖类 `RawFinding` 的 `packageName/packageVersion/fixedVersion/cveId/cvssScore` 填充（Task 11.5 由编排器透传）。

> **CVSS 取分规则（spec §6.2）**：优先 `nvd.V3Score` → `nvd.V2Score` → 各 vendor 最高分；均无 → null。

- [ ] **Step 1: 写 fixture + 失败测试**

`trivy/basic.json`（`trivy fs --format json` 输出；第 2 条 UNKNOWN + 空 CVSS 验证兜底；第 2 个 Result 无 Vulnerabilities 验证兼容）：

```json
{
  "SchemaVersion": 2,
  "ArtifactName": "path/to/workdir",
  "ArtifactType": "filesystem",
  "Results": [
    {
      "Target": "package-lock.json",
      "Class": "lang-pkgs",
      "Type": "npm",
      "Vulnerabilities": [
        {
          "VulnerabilityID": "CVE-2024-1234",
          "PkgName": "lodash",
          "InstalledVersion": "4.17.20",
          "FixedVersion": "4.17.21",
          "Severity": "CRITICAL",
          "CVSS": {
            "nvd": { "V3Vector": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H", "V3Score": 9.8, "V2Vector": "AV:N/AC:L/Au:N/C:C/I:C/A:C", "V2Score": 7.5 }
          },
          "Title": "lodash: prototype pollution",
          "Description": "Affected versions of lodash are vulnerable to prototype pollution."
        },
        {
          "VulnerabilityID": "CVE-2024-5678",
          "PkgName": "axios",
          "InstalledVersion": "0.21.1",
          "FixedVersion": "0.21.4",
          "Severity": "UNKNOWN",
          "CVSS": {},
          "Title": "axios: cross-site scripting",
          "Description": ""
        }
      ]
    },
    {
      "Target": "go.mod",
      "Class": "lang-pkgs",
      "Type": "golang",
      "Vulnerabilities": null
    }
  ]
}
```

`TrivyResultParserTest.kt`：

```kotlin
package com.example.compliance.engineadapter.trivy

import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrivyResultParserTest {
    private val parser = TrivyResultParser()
    private val json = javaClass.getResource("/trivy/basic.json").readText(StandardCharsets.UTF_8)

    @Test
    fun `parses vulnerabilities into dependency raw findings`() {
        val findings = parser.parse(json)
        assertEquals(2, findings.size)
        val f0 = findings[0]
        assertEquals("CVE-2024-1234", f0.engineRuleId)          // engineRuleId = CVE（spec §6.2）
        assertEquals("CVE-2024-1234", f0.cveId)
        assertEquals("lodash", f0.packageName)
        assertEquals("4.17.20", f0.packageVersion)
        assertEquals("4.17.21", f0.fixedVersion)
        assertEquals(9.8, f0.cvssScore)                          // nvd.V3Score 优先
        assertEquals("package-lock.json", f0.filePath)           // Target → filePath
        assertEquals("CRITICAL", f0.severity)                    // 保留原生 severity
        assertNull(f0.line)
    }

    @Test
    fun `unknown severity preserved and empty cvss yields null score`() {
        val f1 = parser.parse(json)[1]
        assertEquals("UNKNOWN", f1.severity)                     // normalize 才映射 → LOW
        assertNull(f1.cvssScore)                                 // 空 CVSS → null
        assertEquals("CVE-2024-5678", f1.cveId)
    }

    @Test
    fun `skips results without vulnerabilities and empty report yields empty list`() {
        assertTrue(parser.parse(json).size == 2)                 // go.mod Result 无 Vulnerabilities → 跳过
        assertTrue(parser.parse("""{"Results":[]}""").isEmpty())
        assertTrue(parser.parse("not json").isEmpty())
    }
}
```

`TrivySeverityMapperTest.kt`：

```kotlin
package com.example.compliance.engineadapter.trivy

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TrivySeverityMapperTest {
    private val mapper = TrivySeverityMapper()

    @Test
    fun `passes through unified severities and defaults unknown to low`() {
        assertEquals("CRITICAL", mapper.map("CRITICAL"))
        assertEquals("HIGH", mapper.map("HIGH"))
        assertEquals("MEDIUM", mapper.map("MEDIUM"))
        assertEquals("LOW", mapper.map("LOW"))
        assertEquals("LOW", mapper.map("UNKNOWN"))   // 兜底（与 SemgrepSeverityMapper 的 else->LOW 一致）
        assertEquals("LOW", mapper.map(""))
    }
}
```

`TrivyAdapterTest.kt`（镜像 GitleaksAdapterTest 形态；依赖字段原样保留）：

```kotlin
package com.example.compliance.engineadapter.trivy

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrivyAdapterTest {
    private val cli = mockk<TrivyCli>()
    private val adapter = TrivyAdapter(cli, TrivyResultParser(), TrivySeverityMapper())

    private val json = javaClass.getResource("/trivy/basic.json").readText(StandardCharsets.UTF_8)
    private val ctx = ScanContext(1L, 1L, "https://git.example.com/repo.git", "main")

    @Test
    fun `execute collect normalize keeps dependency fields and maps severity`() {
        every { cli.run(any()) } returns json
        val execution = adapter.executeScan(ctx)
        assertTrue(execution.success)
        assertTrue(execution.stdoutRef != null)
        val raw = adapter.collectResult(ctx)
        assertEquals(2, raw.size)
        assertEquals("CRITICAL", raw[0].severity)
        assertEquals("lodash", raw[0].packageName)
        val normalized = adapter.normalizeResult(ctx, raw)
        assertEquals("CRITICAL", normalized[0].severity)
        assertEquals("LOW", normalized[1].severity)        // UNKNOWN → LOW
        assertEquals("lodash", normalized[0].packageName)  // 依赖字段原样保留
    }

    @Test
    fun `cli failure maps to unsuccessful execution without stdout file`() {
        every { cli.run(any()) } throws IllegalStateException("trivy exited with code 1")
        val execution = adapter.executeScan(ctx)
        assertTrue(!execution.success)
        assertTrue(adapter.collectResult(ctx).isEmpty())
    }

    @Test
    fun `cleanup clears stdout file`() {
        every { cli.run(any()) } returns json
        adapter.executeScan(ctx)
        adapter.collectResult(ctx)
        adapter.cleanup(ctx)
        assertTrue(adapter.collectResult(ctx).isEmpty())
    }

    @Test
    fun `engine name is TRIVY`() {
        assertEquals("TRIVY", adapter.engine)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-engine-adapter:test --tests "*Trivy*"`
Expected: FAIL —— `Unresolved reference 'TrivyAdapter'` 等（编译失败即 RED）。

- [ ] **Step 3: 实现**

`TrivyCli.kt`：

```kotlin
package com.example.compliance.engineadapter.trivy

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.TimeUnit

interface TrivyCli {
    fun run(targetPath: String): String
}

/** trivy CLI 进程封装（spec §6.1）：`trivy fs <target> --format json --no-progress`。
 *  稳健设计同 GitleaksCli：stdout/stderr 各自重定向独立临时文件（无未读管道），JSON 只从 stdout 读取。
 *  exit 语义：0=成功（命中漏洞不改变退出码，默认无 --exit-code）；非 0 抛异常。 */
@Component
class ProcessTrivyCli(
    @Value("\${app.trivy.timeout-seconds:600}")
    private val timeoutSeconds: Long,
) : TrivyCli {
    override fun run(targetPath: String): String {
        val out = File.createTempFile("trivy-out-", ".json")
        val err = File.createTempFile("trivy-err-", ".log")
        try {
            val cmd = listOf("trivy", "fs", targetPath, "--format", "json", "--no-progress")
            val process = ProcessBuilder(cmd)
                .redirectOutput(out)
                .redirectError(err)
                .start()
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
                throw IllegalStateException("trivy timed out after ${timeoutSeconds}s")
            }
            if (process.exitValue() != 0) {
                throw IllegalStateException("trivy exited with code ${process.exitValue()}")
            }
            return out.readText()
        } finally {
            out.delete(); err.delete()
        }
    }
}
```

`TrivyResultParser.kt`：

```kotlin
package com.example.compliance.engineadapter.trivy

import com.example.compliance.result.engine.RawFinding
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/** trivy fs JSON 解析（spec §6.2）：Results[].Vulnerabilities[] → 依赖类 RawFinding（保留原生 severity）。
 *  engineRuleId = VulnerabilityID（CVE，plan.md `trivy.CVE-XXXX` 粒度）；filePath = Target（锁文件路径）。 */
@Component
class TrivyResultParser {
    private val objectMapper = ObjectMapper()

    fun parse(stdout: String): List<RawFinding> {
        val root = runCatching { objectMapper.readTree(stdout) }.getOrNull()
            ?: return emptyList()
        val results = root.path("Results")
        if (!results.isArray) return emptyList()
        val out = mutableListOf<RawFinding>()
        results.forEach { result ->
            val target = result.path("Target").asText("")
            val vulns = result.path("Vulnerabilities")
            if (!vulns.isArray) return@forEach     // 非漏洞 Result（Class 非 os/library）跳过
            vulns.forEach { v ->
                val cve = v.path("VulnerabilityID").asText("")
                if (cve.isEmpty()) return@forEach
                out += RawFinding(
                    engineRuleId = cve,
                    ruleName = null,
                    filePath = target,
                    line = null,
                    severity = v.path("Severity").asText("UNKNOWN"),
                    message = v.path("Title").takeIf { !it.isMissingNode && it.asText().isNotBlank() }?.asText()
                        ?: v.path("Description").takeIf { !it.isMissingNode }?.asText(),
                    codeSnippet = null,
                    category = null,
                    packageName = v.path("PkgName").takeIf { !it.isMissingNode }?.asText(),
                    packageVersion = v.path("InstalledVersion").takeIf { !it.isMissingNode }?.asText(),
                    fixedVersion = v.path("FixedVersion").takeIf { !it.isMissingNode }?.asText(),
                    cveId = cve,
                    cvssScore = cvssScoreOf(v.path("CVSS")),
                )
            }
        }
        return out
    }

    /** 取分规则（spec §6.2）：优先 nvd.V3Score → nvd.V2Score → 各 vendor 最高分；均无 → null。 */
    private fun cvssScoreOf(cvss: com.fasterxml.jackson.databind.JsonNode): Double? {
        if (cvss.isMissingNode || !cvss.isObject) return null
        val nvd = cvss.path("nvd")
        if (nvd.isObject) {
            nvd.path("V3Score").takeIf { it.isNumber }?.let { return it.asDouble() }
            nvd.path("V2Score").takeIf { it.isNumber }?.let { return it.asDouble() }
        }
        return cvss.fields().asSequence()
            .mapNotNull { (_, v) ->
                v.path("V3Score").takeIf { it.isNumber }?.asDouble()
                    ?: v.path("V2Score").takeIf { it.isNumber }?.asDouble()
            }
            .maxOrNull()
    }
}
```

`TrivySeverityMapper.kt`：

```kotlin
package com.example.compliance.engineadapter.trivy

import org.springframework.stereotype.Component

/** Trivy 原生 severity（CRITICAL/HIGH/MEDIUM/LOW/UNKNOWN）→ 统一等级；UNKNOWN 兜底 LOW（与 SemgrepMapper else->LOW 一致）。 */
@Component
class TrivySeverityMapper {
    fun map(engineSeverity: String): String = when (engineSeverity.uppercase()) {
        "CRITICAL" -> "CRITICAL"
        "HIGH" -> "HIGH"
        "MEDIUM" -> "MEDIUM"
        "LOW" -> "LOW"
        else -> "LOW"
    }
}
```

`TrivyAdapter.kt`：

```kotlin
package com.example.compliance.engineadapter.trivy

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanExecutionResult
import org.springframework.stereotype.Component
import java.io.File

/** Trivy 依赖漏洞适配器（spec §6.4）：五方法镜像 SemgrepAdapter；依赖字段随 RawFinding 原样保留。 */
@Component
class TrivyAdapter(
    private val cli: TrivyCli,
    private val parser: TrivyResultParser,
    private val severityMapper: TrivySeverityMapper,
) : ScanEngineAdapter {

    override val engine: String = "TRIVY"

    override fun prepareScan(context: ScanContext) {
        // 无前置动作
    }

    override fun executeScan(context: ScanContext): ScanExecutionResult {
        val target = scanTarget(context)
        val start = System.currentTimeMillis()
        return runCatching {
            val stdout = cli.run(target)
            val file = stdoutFile(context)
            file.writeText(stdout)
            ScanExecutionResult(success = true, stdoutRef = file.absolutePath)
        }.getOrElse { e ->
            // cli 失败（非 0 退出 / 超时）→ success=false，不落盘 stdout（F1 同款）
            ScanExecutionResult(success = false, errorMessage = e.message, durationMs = System.currentTimeMillis() - start)
        }
    }

    override fun collectResult(context: ScanContext): List<RawFinding> {
        val file = stdoutFile(context)
        if (!file.exists()) return emptyList()
        return parser.parse(runCatching { file.readText() }.getOrDefault("{}"))
    }

    override fun normalizeResult(context: ScanContext, raw: List<RawFinding>): List<RawFinding> =
        raw.map { it.copy(severity = severityMapper.map(it.severity)) }

    override fun cleanup(context: ScanContext) {
        runCatching { stdoutFile(context).delete() }
    }

    private fun stdoutFile(context: ScanContext): File =
        File(System.getProperty("java.io.tmpdir"), "trivy-stdout-${context.scanTaskId}.json")

    private fun scanTarget(context: ScanContext): String = context.workDir ?: context.repoUrl
}
```

> 注：`app.trivy.command` 为文档镜像（同 `app.semgrep.command`，CLI 不消费）。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :module-engine-adapter:test --tests "*Trivy*"`
Expected: PASS（3 测试类全绿）。

- [ ] **Step 5: 全量回归 + 提交**

Run: `./gradlew build` — Expected: BUILD SUCCESSFUL。
Commit（staged 仅 module-engine-adapter/src/main + module-engine-adapter/src/test）：
`feat(engine-adapter): trivy adapter with cli/parser/severity-mapper and fixture tests (m11)`

---

## Task 11.5: 编排器接线 + 配置 + 集成测试（module-scan + app-server）

**Files:**
- Modify: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanOrchestrator.kt:109-112`（`NewFinding` 透传 5 字段）
- Modify: `app-server/src/main/resources/application.yml`（`app.gitleaks.*`、`app.trivy.*`、`checkout-engines` 加 GITLEAKS,TRIVY）
- Create: `app-server/src/test/kotlin/com/example/compliance/scan/M11EngineIntegrationTest.kt`

**Interfaces:**
- Consumes: `NewFinding` 新字段（Task 11.2）；`RawFinding` 新字段（Task 11.1）；Gitleaks/Trivy 适配器已存在（Spring 自动注册进 `EngineAdapterRegistry`，无需手工注册）。
- Produces: 全链路端到端证明 —— STUBG/STUBT 五阶段 + 依赖类 finding 落库（`FindingView` 含依赖字段）+ checkout-engines 配置断言。

> **为何最后**：编排器透传依赖 `NewFinding`/`RawFinding` 新字段（11.1+11.2）；集成测试断言依赖 finding 端到端落库依赖 11.2 分流。

- [ ] **Step 1: 写失败测试**（`M11EngineIntegrationTest.kt`）

镜像 `M8EngineContractIntegrationTest` 的 STUB 模式（object-state + @TestConfiguration）：

```kotlin
package com.example.compliance.scan

import com.example.compliance.AbstractIntegrationTest
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
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M11 引擎契约集成测试（镜像 M8）：STUBG/STUBT 五方法接入 + 依赖类 finding 端到端落库 + checkout-engines 配置断言。
 *  数据前缀 M11-*；STUBG/STUBT 不在 checkout-engines → commitId null。 */
class M11EngineIntegrationTest : AbstractIntegrationTest() {

    object StubState {
        @Volatile var prepared = false
        @Volatile var executed = false
        @Volatile var collected = false
        @Volatile var cleanupCalled = false
    }

    @TestConfiguration
    class StubAdaptersConfig {
        @Bean
        fun stubGAdapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUBG"
            override fun prepareScan(context: ScanContext) { StubState.prepared = true }
            override fun executeScan(context: ScanContext): ScanExecutionResult {
                StubState.executed = true
                return ScanExecutionResult(success = true, durationMs = 5)
            }
            override fun collectResult(context: ScanContext): List<RawFinding> =
                listOf(RawFinding("stub-g-key", "M11 Gitleaks", "src/main/resources/application.yml", 12, "HIGH", "Found a generic API key", "apiKey = \"sk-1234\""))
            override fun normalizeResult(context: ScanContext, raw: List<RawFinding>): List<RawFinding> = raw
            override fun cleanup(context: ScanContext) { StubState.cleanupCalled = true }
        }

        @Bean
        fun stubTAdapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUBT"
            override fun prepareScan(context: ScanContext) { StubState.prepared = true }
            override fun executeScan(context: ScanContext): ScanExecutionResult {
                StubState.executed = true
                return ScanExecutionResult(success = true, durationMs = 5)
            }
            override fun collectResult(context: ScanContext): List<RawFinding> =
                listOf(RawFinding(
                    "stub-t-cve-1", "M11 Trivy", "package-lock.json", null, "CRITICAL",
                    "lodash prototype pollution", null, null,
                    "lodash", "4.17.20", "4.17.21", "CVE-2024-1234", 9.8,
                ))
            override fun normalizeResult(context: ScanContext, raw: List<RawFinding>): List<RawFinding> = raw
            override fun cleanup(context: ScanContext) { StubState.cleanupCalled = true }
        }
    }

    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService
    @Autowired lateinit var lifecyclePort: FindingLifecyclePort
    @Value("\${app.scan.checkout-engines}") lateinit var checkoutEngines: Set<String>

    @Test
    fun `gitleaks-named stub drives five stages with dependency-free finding`() {
        val project = projectService.create(CreateProjectCommand("M11GP", "M11 gitleaks", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("m11g-repo", "https://git.example.com/m11g.git", "GITLAB", "main", "tok"))
        val rule = ruleService.create(CreateRuleCommand("M11-GKEY", "M11 密钥", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUBG", "stub-g-key", null))
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'HIGH'"))
        ruleService.publish(rule.id!!)

        StubState.prepared = false; StubState.executed = false
        StubState.collected = false; StubState.cleanupCalled = false
        val task = scanTaskService.startScan(project.id!!, "STUBG", "main")
        var done = false
        repeat(50) {
            val s = scanTaskService.get(task.id!!).status
            if (s != ScanTaskStatus.PENDING && s != ScanTaskStatus.PREPARING && s != ScanTaskStatus.RUNNING) { done = true; return@repeat }
            Thread.sleep(200)
        }
        assertTrue(done, "scan should finish within timeout")
        assertEquals(ScanTaskStatus.SUCCESS, scanTaskService.get(task.id!!).status)

        assertTrue(StubState.prepared && StubState.executed && StubState.collected && StubState.cleanupCalled)
        assertNull(scanTaskService.get(task.id!!).commitId, "STUBG not in checkout-engines -> commitId null")

        val views = lifecyclePort.findingsForScanTask(task.id!!)
        assertEquals(1, views.size)
        assertEquals("M11-GKEY", views[0].ruleCode)
        assertEquals("STUBG", views[0].engine)
        assertNull(views[0].packageName, "code-class finding has no dependency fields")
    }

    @Test
    fun `trivy-named stub persists dependency finding with dependency fields end to end`() {
        val project = projectService.create(CreateProjectCommand("M11TP", "M11 trivy", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("m11t-repo", "https://git.example.com/m11t.git", "GITLAB", "main", "tok"))
        val rule = ruleService.create(CreateRuleCommand("M11-TRV", "M11 依赖漏洞", "CRITICAL", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUBT", "stub-t-cve-1", null))
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'CRITICAL'"))
        ruleService.publish(rule.id!!)

        val task = scanTaskService.startScan(project.id!!, "STUBT", "main")
        var done = false
        repeat(50) {
            val s = scanTaskService.get(task.id!!).status
            if (s != ScanTaskStatus.PENDING && s != ScanTaskStatus.PREPARING && s != ScanTaskStatus.RUNNING) { done = true; return@repeat }
            Thread.sleep(200)
        }
        assertTrue(done, "scan should finish within timeout")
        assertEquals(ScanTaskStatus.SUCCESS, scanTaskService.get(task.id!!).status)

        val views = lifecyclePort.findingsForScanTask(task.id!!)
        assertEquals(1, views.size)
        val v = views[0]
        assertEquals("M11-TRV", v.ruleCode)
        assertEquals("STUBT", v.engine)
        assertEquals("lodash", v.packageName)                // 依赖类字段端到端落库（11.2 分流 + V12）
        assertEquals("4.17.20", v.packageVersion)
        assertEquals("4.17.21", v.fixedVersion)
        assertEquals("CVE-2024-1234", v.cveId)
        assertEquals(9.8, v.cvssScore)
    }

    @Test
    fun `checkout-engines config contains real engines`() {
        assertTrue("SEMGREP" in checkoutEngines)
        assertTrue("GITLEAKS" in checkoutEngines)
        assertTrue("TRIVY" in checkoutEngines)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :app-server:test --tests "*M11EngineIntegrationTest*"`
Expected: FAIL —— 依赖 finding 断言红（`ScanOrchestrator` 未透传依赖字段 → `FindingView.packageName` 为 null）+ `checkout-engines` 配置断言红。

- [ ] **Step 3: 实现**

`ScanOrchestrator.kt`（第 109-112 行）构造 `NewFinding` 补 5 个透传参数：

```kotlin
            normalized += NewFinding(
                rule.ruleCode, rule.name, rawFinding.filePath, rawFinding.line,
                rawFinding.severity, rawFinding.category, rawFinding.message, rawFinding.codeSnippet,
                rawFinding.packageName, rawFinding.packageVersion, rawFinding.fixedVersion,
                rawFinding.cveId, rawFinding.cvssScore,
            )
```

`application.yml`（`app:` 段追加，并把 `checkout-engines` 改为三引擎）：

```yaml
  gitleaks:
    command: gitleaks
    timeout-seconds: 300
  trivy:
    command: trivy
    timeout-seconds: 600
  scan:
    checkout-engines: SEMGREP,GITLEAKS,TRIVY   # M11：GITLEAKS/TRIVY 都扫检出目录 → 触发 GitCheckout
```

> `checkout-engines` 变更对既有测试零影响：全部测试引擎（STUB*）不在列表，commitId 保持 null（M8 断言仍成立）。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :app-server:test --tests "*M11EngineIntegrationTest*"` — Expected: PASS（3 测试全绿）。
Run: `./gradlew :app-server:test --tests "*M8EngineContractIntegrationTest*"` — Expected: PASS（既有契约测试零回归）。

- [ ] **Step 5: 全量回归 + 提交**

Run: `./gradlew build` — Expected: BUILD SUCCESSFUL（全部 68 任务 + 既有集成套件零回归）。
Commit（staged 仅 module-scan/src/main + app-server/src/main + app-server/src/test）：
`feat(scan,app-server): orchestrator dependency-field passthrough + checkout-engines GITLEAKS/TRIVY + M11 integration tests (m11)`

---

## 验收汇总（M11 完成标准）

1. 双引擎适配器（Gitleaks + Trivy）落地，五方法契约 + 引擎自动注册 + severity 归一化。
2. 依赖类字段（RawFinding/NewFinding/Finding/FindingView + V12）与依赖类指纹（`generateDependency`）首次落地，`upsertByFingerprint` 分流。
3. 编排器透传依赖字段；`checkout-engines` 含 GITLEAKS/TRIVY。
4. 全部 5 任务各自 RED→GREEN→`./gradlew build` 全绿 + commit（未 push）。
5. M11 完成 = 上述落地 + 全量回归（含 M6/M7/M8/M9/ScanPipeline/Report/Rbac 全部既有集成套件）零失败。
