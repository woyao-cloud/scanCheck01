# M13 引擎收尾 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 收尾 M11 终审遗留项——依赖类 finding 硬化（P3-D7 元数据刷新 + P3-D8 both-or-neither 守卫）、四引擎共享 CliExecutor 抽取（P3-D9 纯重构）+ SemgrepCli 诊断对齐、真实二进制 E2E 门控测试。

**Architecture:** 三个子任务相互独立、按序交付：13.1 改 `FindingService.upsertByFingerprint`（module-result，M14 前置）；13.2 在 module-engine-adapter 抽取共享 `CliExecutor`（Semgrep/Gitleaks/Trivy 三个 Process*Cli 改写为薄壳，纯重构）；13.3 在 app-server 加 `APP_SCAN_E2E` 门控的 `RealEngineE2ETest`（本地临时 fixture 目录 + 真实 gitleaks/trivy 二进制全链路扫描）。

**Tech Stack:** Kotlin 2.0.21、Spring Boot 3.3.5、JUnit 5、MockK、Testcontainers（共享 PG）、ProcessBuilder。

**Spec:** `docs/superpowers/specs/2026-09-04-code-compliance-platform-m12-m14-design.md`（§4 M13 + §2 P3-D6/D7/D8/D9 + §7 全局约束）—— spec 是绑定权威，plan 是其论证。

## Global Constraints

- **P2-D4**：`finding.status` 唯一权威；P3-D7 元数据刷新**不触碰**状态转移。
- **P3-D9（纯重构）**：CliExecutor 抽取不改任何 CLI 对外语义；**既有 30 适配器测试 + 全量 build 必须全绿**。
- **P3-D8**：依赖分流 both-or-neither 守卫；M14 Dependency-Check 必须遵守（本里程碑落地守卫本身）。
- **P3-D6（门控）**：RealEngineE2ETest 用 `@EnabledIfEnvironmentVariable(named="APP_SCAN_E2E", matches="true")`；CI/测试默认不装二进制、默认跳过。
- **共享 Testcontainers**：app-server 集成测试共享 PG 容器（`max_connections=300` 保持）；数据前缀 `M13-*`；`SmokeFirstClassOrderer` 不变。
- 既有约定：Process*Cli 无直接单测（镜像先例），验证靠 30 适配器测试 + 全量 build；`VersionStatus` 等枚举包路径不变。

---

### Task 13.1: 依赖类硬化 — P3-D7 元数据刷新 + P3-D8 单边守卫（M14 前置）

**Files:**
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/application/FindingService.kt:56-101`（`upsertByFingerprint`）
- Test: `module-result/src/test/kotlin/com/example/compliance/result/application/FindingServiceTest.kt`

**Interfaces:**
- Produces: `upsertByFingerprint(projectId: Long, scanTaskId: Long, engine: String, findings: List<NewFinding>): UpsertResult` 签名不变；行为变化：(a) 单边依赖 finding 抛 `IllegalArgumentException`；(b) REAPPEARED 依赖 finding 刷新 `packageVersion`/`fixedVersion`/`cvssScore`。
- Consumes: `NewFinding` 13 字段（`packageName/packageVersion/fixedVersion/cveId/cvssScore` 尾部默认值）；`FingerprintGenerator.generateDependency(projectId, packageName, packageVersion, cveId)`。

- [ ] **Step 1: 写失败测试（3 个新用例）**

在 `FindingServiceTest.kt` 末尾追加（补 `import kotlin.test.assertFailsWith`）：

```kotlin
@Test
fun `reappearing dependency finding refreshes remediation metadata P3-D7`() {
    val fpDep = "fp-dep-refresh"
    val existing = Finding().apply {
        id = 8L; projectId = 9L; status = FindingStatus.NEW; fingerprint = fpDep; occurrenceCount = 1
        packageName = "lodash"; cveId = "CVE-2024-1234"
        packageVersion = "4.17.19"; fixedVersion = "4.17.20"; cvssScore = 7.5.toBigDecimal()
    }
    every { fingerprintGenerator.generateDependency(9L, "lodash", "4.17.20", "CVE-2024-1234") } returns fpDep
    every { findingRepository.findByProjectIdAndFingerprint(9L, fpDep) } returns existing
    every { findingRepository.save(any<Finding>()) } answers { firstArg() }
    every { historyRepository.save(any<FindingHistory>()) } answers { firstArg<FindingHistory>() }

    val dep = NewFinding("M11TRV", "lodash proto pollution", "package-lock.json", null, "CRITICAL", null, "pollution", null,
        "lodash", "4.17.20", "4.17.21", "CVE-2024-1234", 9.8)
    val result = findingService.upsertByFingerprint(9L, 63L, "TRIVY", listOf(dep))

    assertEquals(UpsertResult(0, 1), result)
    assertEquals(2, existing.occurrenceCount)
    assertEquals("4.17.20", existing.packageVersion)   // 刷新自 incoming —— advisory 更新后不陈旧
    assertEquals("4.17.21", existing.fixedVersion)
    assertEquals(9.8.toBigDecimal(), existing.cvssScore)
    assertEquals(FindingStatus.NEW, existing.status)   // P2-D4: 不碰 status
}

@Test
fun `single-sided dependency finding throws IllegalArgumentException P3-D8`() {
    // both-or-neither: 仅 packageName 或仅 cveId 是上游适配器 bug，显式失败优于 NPE
    val packageOnly = NewFinding("M11TRV", "p", "package-lock.json", null, "HIGH", null, null, null,
        "lodash", null, null, null, null)
    assertFailsWith<IllegalArgumentException> {
        findingService.upsertByFingerprint(9L, 64L, "TRIVY", listOf(packageOnly))
    }
    val cveOnly = NewFinding("M11TRV", "p", "package-lock.json", null, "HIGH", null, null, null,
        null, null, null, "CVE-2024-1234", null)
    assertFailsWith<IllegalArgumentException> {
        findingService.upsertByFingerprint(9L, 65L, "TRIVY", listOf(cveOnly))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-result:test --tests "*FindingServiceTest*" -i`
Expected: `reappearing dependency finding refreshes remediation metadata` 断言失败（refresh 未实现，`existing.packageVersion` 仍 "4.17.19"）；两个单边用例因未抛异常而失败。

- [ ] **Step 3: 实现（两处改动）**

在 `FindingService.kt` 的 `upsertByFingerprint` 中，把指纹分流块（现第 57-61 行 `if (f.packageName != null || f.cveId != null)` + `!!`）替换为：

```kotlin
// M13 P3-D8（M14 前置）: 依赖类判定收紧为 both-or-neither —— 单边（仅 packageName 或仅 cveId）是
// 上游适配器 bug（契约保证 Trivy 恒两者同设、Gitleaks/代码类恒两者不设），显式失败优于 NPE。
if ((f.packageName == null) != (f.cveId == null)) {
    throw IllegalArgumentException(
        "dependency finding requires both packageName and cveId, got: packageName=${f.packageName}, cveId=${f.cveId}"
    )
}
val fingerprint = if (f.packageName != null && f.cveId != null)
    fingerprintGenerator.generateDependency(projectId, f.packageName, f.packageVersion, f.cveId)
else
    fingerprintGenerator.generate(projectId, f.ruleCode, f.filePath, f.lineNumber, f.codeSnippet)
```

在 REAPPEARED 分支（现第 89-91 行 `existing.occurrenceCount += 1` / `lastSeenAt` 之后、`findingRepository.save(existing)` 之前）插入：

```kotlin
// M13 P3-D7: 依赖类 finding 复现时刷新整改指导元数据（advisory 更新后 fixedVersion/cvss 不再陈旧）。
// 不碰 finding.status（P2-D4 状态权威不变），只刷 packageVersion/fixedVersion/cvssScore。
if (existing.packageName != null && existing.cveId != null) {
    existing.packageVersion = f.packageVersion
    existing.fixedVersion = f.fixedVersion
    existing.cvssScore = f.cvssScore?.toBigDecimal()
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :module-result:test`
Expected: 全绿（既有 6 用例不变通过——`reappearing dependency finding increments occurrence` 的 existing 无依赖字段故 refresh 跳过；`dependency finding uses dependency fingerprint` 两者同设走依赖路径；`code finding path is unchanged` 两者均 null 走代码路径）。

- [ ] **Step 5: 提交**

```bash
git add module-result/src/main/kotlin/com/example/compliance/result/application/FindingService.kt module-result/src/test/kotlin/com/example/compliance/result/application/FindingServiceTest.kt
git commit -m "feat(result): dependency finding metadata refresh on reappear + both-or-neither guard (m13)"
```

---

### Task 13.2: CliExecutor 抽取 + SemgrepCli 诊断对齐（P3-D9 纯重构）

**Files:**
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/cli/CliExecutor.kt`
- Modify: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/semgrep/SemgrepCli.kt`
- Modify: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/gitleaks/GitleaksCli.kt`
- Modify: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/trivy/TrivyCli.kt`

**Interfaces:**
- Produces: `CliExecutor(timeoutSeconds: Long)`，方法 `fun run(command: List<String>, label: String, config: Config): String`；嵌套 `class Config(mergeErrorStream: Boolean, successExitCodes: Set<Int>, resultFile: File? = null, includeStdoutTail: Boolean = false)`。
- Consumes（改写后薄壳语义与原实现逐字一致）：
  - Semgrep：`redirectErrorStream(true)` 单文件；JSON 读 stdout 文件；成功退出码 `{0,1}`；失败（timeout/exit 非成功）异常补 stderr tail（**spec 4.2 新增**，对齐 Gitleaks/Trivy）。
  - Gitleaks：双文件；JSON 来源 `resultFile`（--report-path）；成功 `{0,1}`；失败补 stderr tail。
  - Trivy：双文件；JSON 读 stdout 文件；成功 `{0}`；失败补 stderr+stdout tail。

- [ ] **Step 1: 创建 CliExecutor**

`module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/cli/CliExecutor.kt`：

```kotlin
package com.example.compliance.engineadapter.cli

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 共享 CLI 进程执行器（spec P3-D9）：参数化超时、stdout/stderr 重定向模式、成功退出码、JSON 来源、失败 tail 诊断。
 * 纯重构抽取 —— Semgrep/Gitleaks/Trivy 三 Process*Cli 对外语义不变（Semgrep 额外获得失败 tail 诊断，spec 4.2）。
 * 稳健设计（R-8.2-b 教训）：stdout/stderr 各自重定向独立临时文件（或合并单文件），绝无未读管道 → 不假超时。
 */
class CliExecutor(private val timeoutSeconds: Long) {

    /** 一次执行的可变参数。 */
    class Config(
        val mergeErrorStream: Boolean,          // semgrep=true（redirectErrorStream 合并单文件）；gitleaks/trivy=false（双文件）
        val successExitCodes: Set<Int>,         // semgrep={0,1}（0=clean、1=命中）；gitleaks={0,1}；trivy={0}
        val resultFile: File? = null,           // gitleaks=--report-path 文件（JSON 来源）；null → 读 stdout 文件
        val includeStdoutTail: Boolean = false, // trivy=true；gitleaks=false
    )

    fun run(command: List<String>, label: String, config: Config): String {
        val out = File.createTempFile("cli-out-", ".log")
        val err = if (config.mergeErrorStream) null else File.createTempFile("cli-err-", ".log")
        try {
            val pb = ProcessBuilder(command)
            pb.redirectOutput(out)
            if (config.mergeErrorStream) pb.redirectErrorStream(true) else pb.redirectError(err!!)
            val process = pb.start()
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
                throw IllegalStateException("$label timed out after ${timeoutSeconds}s; ${diagTail(config, err, out)}")
            }
            val code = process.exitValue()
            if (code !in config.successExitCodes) {
                throw IllegalStateException("$label exited with code $code; ${diagTail(config, err, out)}")
            }
            return if (config.resultFile != null) {
                if (config.resultFile.exists()) config.resultFile.readText() else "[]"
            } else {
                out.readText()
            }
        } finally {
            out.delete(); err?.delete(); config.resultFile?.delete()
        }
    }

    /** 失败诊断：merged 模式 stderr 已并流 → 读合并文件尾部；split 模式读 err（+ 可选 stdout）尾部。 */
    private fun diagTail(config: Config, err: File?, out: File): String =
        if (config.mergeErrorStream) "stderr: ${tailOf(out)}"
        else {
            val parts = mutableListOf("stderr: ${tailOf(err!!)}")
            if (config.includeStdoutTail) parts += "stdout: ${tailOf(out)}"
            parts.joinToString("; ")
        }

    private fun tailOf(file: File): String =
        if (file.exists()) file.readText().takeLast(500) else ""
}
```

- [ ] **Step 2: 改写三个薄壳**

`SemgrepCli.kt` 的 `ProcessSemgrepCli`（保留接口 `SemgrepCli` 与 `@Component`/`@Value` 注入不变，仅替换 `run` 体与私有字段）：

```kotlin
@Component
class ProcessSemgrepCli(
    @Value("\${app.semgrep.timeout-seconds:300}")
    private val timeoutSeconds: Long,
) : SemgrepCli {
    private val executor = CliExecutor(timeoutSeconds)
    override fun run(targetPath: String, ref: String?): String {
        val cmd = mutableListOf("semgrep", "--json", "--no-rewrite-rule-ids")
        ref?.let { cmd += listOf("--baseline-commit", it) }
        cmd += targetPath
        return executor.run(
            command = cmd,
            label = "semgrep",
            config = CliExecutor.Config(
                mergeErrorStream = true,
                successExitCodes = setOf(0, 1),
            ),
        )
    }
}
```

`GitleaksCli.kt` 的 `ProcessGitleaksCli`（保留接口与注入；`report` 临时文件由薄壳创建并作为 `resultFile` 传入，执行器负责 finally 清理）：

```kotlin
@Component
class ProcessGitleaksCli(
    @Value("\${app.gitleaks.timeout-seconds:300}")
    private val timeoutSeconds: Long,
) : GitleaksCli {
    private val executor = CliExecutor(timeoutSeconds)
    override fun run(targetPath: String): String {
        val report = File.createTempFile("gitleaks-report-", ".json")
        return executor.run(
            command = listOf("gitleaks", "dir", targetPath,
                "--report-format", "json", "--report-path", report.absolutePath, "--no-banner"),
            label = "gitleaks",
            config = CliExecutor.Config(
                mergeErrorStream = false,
                successExitCodes = setOf(0, 1),
                resultFile = report,
            ),
        )
    }
}
```

`TrivyCli.kt` 的 `ProcessTrivyCli`（保留接口与注入）：

```kotlin
@Component
class ProcessTrivyCli(
    @Value("\${app.trivy.timeout-seconds:600}")
    private val timeoutSeconds: Long,
) : TrivyCli {
    private val executor = CliExecutor(timeoutSeconds)
    override fun run(targetPath: String): String =
        executor.run(
            command = listOf("trivy", "fs", targetPath, "--format", "json", "--no-progress"),
            label = "trivy",
            config = CliExecutor.Config(
                mergeErrorStream = false,
                successExitCodes = setOf(0),
                includeStdoutTail = true,
            ),
        )
}
```

清理三文件不再使用的 import（`SemgrepCli.kt`/`GitleaksCli.kt`/`TrivyCli.kt` 移除 `java.util.concurrent.TimeUnit` 与不再用的 `File` 用法——按实际剩余引用删）。

- [ ] **Step 3: 验证纯重构（全量测试绿）**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL。module-engine-adapter 30 适配器测试全绿（适配器 mock `SemgrepCli/GitleaksCli/TrivyCli` 接口，Process*Cli 重构不影响）；全量 68 tasks 绿。不新增 CliExecutor 直接单测（镜像「Process*Cli 无直接测试」先例；spec 4.2 验证门槛即「30 适配器测试 + 全量 build 全绿」）。

- [ ] **Step 4: 提交**

```bash
git add module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/cli/CliExecutor.kt module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/semgrep/SemgrepCli.kt module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/gitleaks/GitleaksCli.kt module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/trivy/TrivyCli.kt
git commit -m "refactor(engine-adapter): extract shared CliExecutor + semgrep failure stderr tail (m13)"
```

---

### Task 13.3: RealEngineE2ETest（APP_SCAN_E2E 门控）

**Files:**
- Create: `app-server/src/test/kotlin/com/example/compliance/scan/RealEngineE2ETest.kt`

**Interfaces:**
- Consumes: `ProjectService.create(CreateProjectCommand(code, name, description, ownerUserId))`、`projectService.bindRepository(projectId, BindRepositoryCommand(code, gitUrl, type, branch, token))`（gitUrl 传本地 fixture 目录绝对路径 → `CommandGitCheckout.isLocal` 命中，跳过 clone、`workDir=目录`、`commitId=null`）；`RuleService.create/addEngineBinding/publish`；`ScanTaskService.startScan(projectId, engine, ref)`；`FindingLifecyclePort.findingsForScanTask(scanTaskId)` / `findingsByProject(projectId, null)`；`FindingRepository.findByProjectIdAndFingerprint(projectId, fingerprint)`；`FingerprintGenerator.generateDependency(projectId, packageName, packageVersion, cveId)`。引擎 `GITLEAKS`/`TRIVY` 已在 `app.scan.checkout-engines`（M11 测试已断言）。

- [ ] **Step 1: 写门控测试类**

`app-server/src/test/kotlin/com/example/compliance/scan/RealEngineE2ETest.kt`：

```kotlin
package com.example.compliance.scan

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.project.application.BindRepositoryCommand
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import com.example.compliance.result.application.FindingLifecyclePort
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.result.infrastructure.FingerprintGenerator
import com.example.compliance.rule.application.AddEngineBindingCommand
import com.example.compliance.rule.application.CreateRuleCommand
import com.example.compliance.rule.application.RuleService
import com.example.compliance.scan.application.ScanTaskService
import com.example.compliance.scan.domain.ScanTaskStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M13 真实引擎 E2E（spec 4.1，门控）：本机安装 gitleaks/trivy 二进制 + 置 APP_SCAN_E2E=true 才运行；
 * CI/默认不装二进制 → 类级 @EnabledIfEnvironmentVariable 整体跳过。
 * 数据前缀 M13-*。fixture 在 @BeforeEach 建本地临时目录（gitleaks 敏感密钥 + trivy 漏洞 package-lock.json），
 * 绑定为项目 repo 本地路径 → checkout 跳过 clone（commitId=null）、adapter 直接扫目录。
 * 注意：trivy 报告的 CVE 取决于本机漏洞库（~/.cache/trivy）。fixture 用 lodash@4.17.20（经典 CVE-2021-23337，
 * fixed 4.17.21）；若本机库报告不同 CVE，用 `trivy fs <fixture目录>` 确认后调整本类的绑定与指纹常量。
 */
@EnabledIfEnvironmentVariable(named = "APP_SCAN_E2E", matches = "true")
class RealEngineE2ETest : AbstractIntegrationTest() {

    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService
    @Autowired lateinit var lifecyclePort: FindingLifecyclePort
    @Autowired lateinit var findingRepository: FindingRepository
    @Autowired lateinit var fingerprintGenerator: FingerprintGenerator

    private lateinit var fixtureDir: Path

    @BeforeEach
    fun createFixture() {
        fixtureDir = Files.createTempDirectory("m13-e2e-")
        // gitleaks fixture：AWS 文档公开示例凭证（AKIA 访问密钥 + 对应 secret）→ gitleaks aws-access-token 规则
        Files.writeString(fixtureDir.resolve("aws-credentials.txt"),
            "aws_access_key_id = AKIAIOSFODNN7EXAMPLE\naws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY\n")
        // trivy fixture：lodash@4.17.20（CVE-2021-23337，fixed 4.17.21）
        Files.writeString(fixtureDir.resolve("package-lock.json"),
            """{"name":"m13-fixture","version":"1.0.0","lockfileVersion":3,"requires":true,
               |"packages":{"node_modules/lodash":{"version":"4.17.20",
               |"resolved":"https://registry.npmjs.org/lodash/-/lodash-4.17.20.tgz",
               |"integrity":"sha512-XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX","dev":true}}}""".trimMargin())
    }

    @AfterEach
    fun deleteFixture() {
        runCatching {
            Files.walk(fixtureDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun pollTask(taskId: Long): ScanTaskStatus {
        var done = false
        repeat(150) {
            val s = scanTaskService.get(taskId).status
            if (s != ScanTaskStatus.PENDING && s != ScanTaskStatus.PREPARING && s != ScanTaskStatus.RUNNING) { done = true; return@repeat }
            Thread.sleep(200)
        }
        assertTrue(done, "real scan should finish within 30s")
        return scanTaskService.get(taskId).status
    }

    @Test
    fun `real gitleaks scan detects fixture secret end to end`() {
        val project = projectService.create(CreateProjectCommand("M13GE", "M13 gitleaks e2e", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("m13g-repo", fixtureDir.toString(), "LOCAL", "main", null))
        val rule = ruleService.create(CreateRuleCommand("M13-GE2E", "M13 密钥", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("GITLEAKS", "aws-access-token", null))
        ruleService.publish(rule.id!!)

        val task = scanTaskService.startScan(project.id!!, "GITLEAKS", "main")
        assertEquals(ScanTaskStatus.SUCCESS, pollTask(task.id!!))

        val views = lifecyclePort.findingsForScanTask(task.id!!)
        assertTrue(views.isNotEmpty(), "real gitleaks should hit the fixture secret")
        assertEquals("GITLEAKS", views[0].engine)
        assertNotNull(views[0].filePath)
        assertEquals("M13-GE2E", views[0].ruleCode)
        assertTrue(scanTaskService.get(task.id!!).commitId == null, "local dir -> checkout skipped -> commitId null")
    }

    @Test
    fun `real trivy scan persists dependency finding and re-scan refreshes metadata`() {
        val project = projectService.create(CreateProjectCommand("M13TE", "M13 trivy e2e", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("m13t-repo", fixtureDir.toString(), "LOCAL", "main", null))
        val rule = ruleService.create(CreateRuleCommand("M13-TE2E", "M13 依赖漏洞", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("TRIVY", "CVE-2021-23337", null))
        ruleService.publish(rule.id!!)

        // 首扫：依赖 finding 落库（5 依赖字段齐全）
        val task1 = scanTaskService.startScan(project.id!!, "TRIVY", "main")
        assertEquals(ScanTaskStatus.SUCCESS, pollTask(task1.id!!))
        val first = lifecyclePort.findingsByProject(project.id!!, null)
        assertTrue(first.isNotEmpty(), "real trivy should hit lodash 4.17.20 in fixture")
        val v = first[0]
        assertEquals("M13-TE2E", v.ruleCode)
        assertEquals("TRIVY", v.engine)
        assertEquals("lodash", v.packageName)
        assertEquals("CVE-2021-23337", v.cveId)
        assertNotNull(v.fixedVersion)
        assertNotNull(v.cvssScore)
        assertEquals(1, v.occurrenceCount)

        // 复扫（同 fixture）：REAPPEARED 路径 —— occurrenceCount 递增、元数据保持（P3-D7 真实验证；
        // fixedVersion 的「变化」由 13.1 单测钉住，真实二扫无法令本机 trivy 库在两秒内更新）
        val task2 = scanTaskService.startScan(project.id!!, "TRIVY", "main")
        assertEquals(ScanTaskStatus.SUCCESS, pollTask(task2.id!!))
        val fp = fingerprintGenerator.generateDependency(project.id!!, "lodash", "4.17.20", "CVE-2021-23337")
        val entity = findingRepository.findByProjectIdAndFingerprint(project.id!!, fp)
        assertNotNull(entity)
        assertEquals(2, entity.occurrenceCount)
        assertEquals("4.17.21", entity.fixedVersion)   // 刷新后元数据仍正确（P2-D4 不碰 status）
    }
}
```

- [ ] **Step 2: 无门控时编译 + 测试跳过验证**

Run: `./gradlew :app-server:compileTestKotlin` → 编译通过。再 Run: `./gradlew :app-server:test --tests "*RealEngineE2ETest*"` → 未置 `APP_SCAN_E2E` 时**跳过**（`@EnabledIfEnvironmentVariable`），BUILD SUCCESSFUL 且 0 执行。

- [ ] **Step 3: 门控下真实运行（本机，可选验证）**

本机装有 gitleaks/trivy 时，设置 `APP_SCAN_E2E=true` 运行验证：
Run: `APP_SCAN_E2E=true ./gradlew :app-server:test --tests "*RealEngineE2ETest*"`
Expected: 两个用例绿（真实二进制扫描本地 fixture）。若 trivy 报告 CVE 与本类绑定不一致，按类 KDoc 提示用 `trivy fs <fixture>` 核对后调整绑定/指纹常量（门控手动测试，允许适配本机引擎版本）。

- [ ] **Step 4: 提交**

```bash
git add app-server/src/test/kotlin/com/example/compliance/scan/RealEngineE2ETest.kt
git commit -m "test(app-server): gated real-engine E2E for gitleaks/trivy with re-scan metadata refresh (m13)"
```

---

## Self-Review（写计划时完成）

**Spec 覆盖：**
- 4.1 真实二进制 E2E（门控 + fixture 断言 + 复扫刷新验证）→ Task 13.3 ✓
- 4.2 SemgrepCli stderr tail + CliExecutor 抽取（纯重构，30 测试 + 全量 build 绿）→ Task 13.2 ✓
- 4.3 P3-D7 REAPPEARED 依赖元数据刷新 + FindingServiceTest 补测试 → Task 13.1 ✓
- 4.4 P3-D8 both-or-neither 守卫 + 单边异常测试（M14 前置）→ Task 13.1 ✓

**Placeholder scan：** 无 TBD/TODO；每步含完整代码。

**Type 一致性：** `CliExecutor.Config` 字段名三薄壳逐字一致；`upsertByFingerprint` 签名不变；`generateDependency` 签名与 M11 一致；`BindRepositoryCommand`/`CreateRuleCommand`/`AddEngineBindingCommand`/`ScanTaskService.startScan` 参数与 M11 集成测试逐字一致。

**Rulings（写计划时固化）：**
- **R-M13-1**（P3-D8 守卫消息格式）：`"dependency finding requires both packageName and cveId, got: packageName=..., cveId=..."`。why：spec 4.4 明文「带清晰消息」，该格式含两侧实值便于定位上游 bug。cost if wrong：无。
- **R-M13-2**（Semgrep 退出码语义）：`successExitCodes = setOf(0, 1)` 替代原 `exitValue() >= 2` 抛异常。why：原式对负数退出码（信号杀）漏判为成功——F1「绝不产出假干净扫描」原则下应为失败；POSIX 信号死退出码 128+N 恒 >=2，实际仅 Windows 极端场景受影响，可辩护为对齐 Gitleaks/Trivy 的收紧。cost if wrong：极小角落在语义上更严格，方向正确。
- **R-M13-3**（真实 E2E 复扫验证边界）：真实 trivy 二扫无法令本机漏洞库在两秒内产生 fixedVersion 变化 → 真实验证只断言 occurrenceCount 递增 + 元数据保持正确；「刷新变化」语义由 13.1 单测钉住。why：spec 4.1 的「验证 fixedVersion 刷新」在真实二扫下不可观测（trivy 库静态），单测才是权威。cost if wrong：门控测试少验证一个真实变化场景，可逆（单测已覆盖语义）。
- **R-M13-4**（CliExecutor 无直接单测）：镜像「Process*Cli 无直接测试」既有先例；spec 4.2 明文验证门槛即「30 适配器测试 + 全量 build 全绿」。why：进程执行器跨平台（Windows/Linux）真进程测试复杂且 spec 未要求。cost if wrong：共享执行器无直接覆盖，未来加引擎时靠全量 build 兜底。

**Pre-flight conflict scan：**

| 任务对 | 生产 vs 消费 | 结论 |
|---|---|---|
| 13.1 → 13.3 | `upsertByFingerprint` REAPPEARED 刷新（P3-D7）→ RealEngineE2ETest 复扫断言 occurrenceCount=2 + 元数据 | 签名不变、行为扩展；13.3 依赖 13.1 先落地 |
| 13.1 → M14 | P3-D8 守卫 → M14 Dependency-Check both-or-neither | 守卫先行，M14 计划需遵守 |
| 13.2 ↔ 自身 | CliExecutor.Config 参数 vs 三薄壳调用 | 逐字一致；Semgrep 单文件 / Gitleaks resultFile / Trivy stdout tail 三分支与现语义一致 |
| 13.2 → 既有测试 | Process*Cli 改写 vs 30 适配器测试（mock 接口） | 接口不变 → 适配器测试不受影响；`@Value`/`@Component` 保留 |
| 13.3 ↔ 自身 | fixture CVE/规则绑定 vs 指纹常量；checkout-engines 含 GITLEAKS/TRIVY（M11 已断言） | 一致；本地目录 → checkout 跳过 clone → commitId null |
| 13.3 → 既有集成测试 | 新类数据前缀 M13-* vs M11/M12 各前缀 | 无交叉（M11 M11-*/M12 M12-*） |
