# M14 更多引擎 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 接入两个新扫描引擎——Dependency-Check（OWASP 依赖类）与 Detekt（Kotlin 静态分析代码类），并镜像 M11 完成桩引擎端到端集成测试。P3-D8 both-or-neither 守卫（M13 已落地）是 M14 前置。

**Architecture:** 两个引擎各自独立任务（14.1 DC 镜像 TrivyAdapter 形态、14.2 Detekt 镜像 SemgrepAdapter 形态），共用 M13 抽取的 CliExecutor（resultFile 模式，Gitleaks 先例）。第三个任务在 app-server 加桩引擎集成测试 + 配置（checkout-engines、timeout keys）。无 schema 变更。

**Tech Stack:** Kotlin 2.0.21、Spring Boot 3.3.5、Jackson、MockK、JUnit 5、Testcontainers（共享 PG）、CliExecutor（M13）。

**Spec:** `docs/superpowers/specs/2026-09-04-code-compliance-platform-m12-m14-design.md`（§5 M14 + §7 全局约束）—— spec 是绑定权威，plan 是其论证。

## Global Constraints

- **P3-D8（M13 前置，M14 必须遵守）**：依赖分流 both-or-neither——DC 每个漏洞恒设 packageName+cveId（spec §5.1 保证）；Detekt 代码类恒不设依赖字段。
- **引擎契约**：`ScanEngineAdapter` 五方法；`RawFinding.severity` 已归一化（LOW/MEDIUM/HIGH/CRITICAL）——**STUB 桩也必须返回归一化 severity**（不得返回 "WARNING" 等原生值）。
- **checkout-engines 门控**：真实引擎 `DEPENDENCYCHECK`/`DETEKT` 加入列表 → 触发 GitCheckout（spec §5.1/§5.2 明文）；`STUBDC`/`STUBDET` 不在列表 → commitId null。
- **CliExecutor 复用（M13）**：新 CLI 薄壳走 resultFile 模式（Gitleaks 先例）；Process*Cli 无直接单测（先例），门禁 = 适配器测试 + 全量 build 绿。
- **集成测试（spec §5.3）**：镜像 M11（STUB 桩、setEvaluationPolicy FAIL、50×200ms poll、commitId null、`M14-*` 数据前缀）；`@AfterEach` 清理临时文件（R-M11-4 内建）。
- **全局红线（spec §7）**：不硬编码合规规则；历史扫描结果不可改；无 DDL 变更；共享 Testcontainers（max_connections=300 保持）；`SmokeFirstClassOrderer` 不变。

---

### Task 14.1: Dependency-Check 引擎（OWASP，依赖类）

**Files:**
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/dependencycheck/DependencyCheckCli.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/dependencycheck/DependencyCheckResultParser.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/dependencycheck/DependencyCheckSeverityMapper.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/dependencycheck/DependencyCheckAdapter.kt`
- Test: `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/dependencycheck/DependencyCheckResultParserTest.kt`
- Test: `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/dependencycheck/DependencyCheckSeverityMapperTest.kt`
- Test: `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/dependencycheck/DependencyCheckAdapterTest.kt`
- Fixture: `module-engine-adapter/src/test/resources/dependencycheck/basic.json`

**Interfaces:**
- Produces: `DependencyCheckCli.run(targetPath: String, scanTaskId: Long): String`（返回 dependency-check-report.json 内容）；`DependencyCheckResultParser.parse(report: String): List<RawFinding>`；`DependencyCheckSeverityMapper.map(engineSeverity: String): String`；`DependencyCheckAdapter : ScanEngineAdapter`（`engine = "DEPENDENCYCHECK"`）。
- Consumes: `ScanContext.scanTaskId/projectId/repoUrl/ref/workDir/commitId`；`RawFinding` 13 字段（8 代码 + 5 依赖尾部默认）；`CliExecutor(timeoutSeconds).run(command, label, config)`（M13）；`@Value("\${app.dependencycheck.timeout-seconds:600}")`。

- [ ] **Step 1: 写失败测试 + fixture**

`src/test/resources/dependencycheck/basic.json`（DC JSON 报告形态：`dependencies[].{fileName,filePath,packages[].id,vulnerabilities[]}`）：

```json
{
  "reportSchema": "1.1",
  "scanInfo": { "engineVersion": "10.0.0" },
  "projectInfo": { "name": "M14 fixture" },
  "dependencies": [
    {
      "fileName": "package-lock.json",
      "filePath": "/workspace/package-lock.json",
      "packages": [ { "id": "pkg:npm/lodash@4.17.20", "confidence": "HIGHEST" } ],
      "vulnerabilityIds": [ { "id": "CVE-2021-23337", "confidence": "HIGHEST" } ],
      "vulnerabilities": [
        {
          "source": "NVD",
          "name": "CVE-2021-23337",
          "severity": "High",
          "cvssv3": { "baseScore": 9.8, "baseSeverity": "CRITICAL" },
          "cvssv2": { "score": 10.0, "severity": "HIGH" },
          "description": "Command Injection in lodash 4.17.20",
          "notes": "",
          "references": []
        }
      ]
    },
    {
      "fileName": "server.js",
      "filePath": "/workspace/server.js",
      "packages": [],
      "vulnerabilities": [
        {
          "source": "NVD",
          "name": "CVE-2022-0002",
          "severity": "Medium",
          "cvssv3": { "baseScore": 5.3 },
          "description": "Prototype pollution"
        }
      ]
    }
  ]
}
```

`DependencyCheckResultParserTest.kt`：

```kotlin
package com.example.compliance.engineadapter.dependencycheck

import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DependencyCheckResultParserTest {
    private val parser = DependencyCheckResultParser()
    private val report = javaClass.getResource("/dependencycheck/basic.json").readText(StandardCharsets.UTF_8)

    @Test
    fun `parses dependency vulnerabilities with package fields and null fixedVersion`() {
        val out = parser.parse(report)
        assertEquals(2, out.size)
        val first = out[0]
        assertEquals("CVE-2021-23337", first.engineRuleId)
        assertEquals("CVE-2021-23337", first.cveId)
        assertEquals("/workspace/package-lock.json", first.filePath)
        assertEquals("lodash", first.packageName)          // 自 pkg:npm/lodash@4.17.20 推断
        assertEquals("4.17.20", first.packageVersion)
        assertNull(first.fixedVersion)                     // DC 无修复版本字段（spec §5.1）
        assertEquals(9.8, first.cvssScore)                 // CVSSv3.baseScore 优先
        assertEquals("High", first.severity)               // 原生透传（映射在 normalizeResult）
        assertEquals("Command Injection in lodash 4.17.20", first.message)
        val second = out[1]
        assertEquals("CVE-2022-0002", second.engineRuleId)
        assertEquals("server.js", second.packageName)      // 无 packages → fileName 兜底（P3-D8 恒非空）
        assertNull(second.packageVersion)
        assertEquals(5.3, second.cvssScore)
    }

    @Test
    fun `invalid or empty input yields empty list`() {
        assertTrue(parser.parse("not json").isEmpty())
        assertTrue(parser.parse("{}").isEmpty())
    }
}
```

`DependencyCheckSeverityMapperTest.kt`：

```kotlin
package com.example.compliance.engineadapter.dependencycheck

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DependencyCheckSeverityMapperTest {
    private val mapper = DependencyCheckSeverityMapper()

    @Test
    fun `maps native dc severities`() {
        assertEquals("HIGH", mapper.map("High"))
        assertEquals("MEDIUM", mapper.map("Medium"))
        assertEquals("LOW", mapper.map("Low"))
        assertEquals("HIGH", mapper.map("high"))
    }

    @Test
    fun `maps unknown severities to MEDIUM per spec`() {
        assertEquals("MEDIUM", mapper.map("CRITICAL"))     // spec §5.1: only HIGH/MEDIUM/LOW 直通
        assertEquals("MEDIUM", mapper.map("UNKNOWN"))
        assertEquals("MEDIUM", mapper.map(""))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-engine-adapter:compileTestKotlin`
Expected: FAIL——`DependencyCheckResultParser`/`DependencyCheckSeverityMapper` 未定义（编译错误）。

- [ ] **Step 3: 实现 parser + mapper**

`DependencyCheckResultParser.kt`：

```kotlin
package com.example.compliance.engineadapter.dependencycheck

import com.example.compliance.result.engine.RawFinding
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/** dependency-check-report.json 解析（spec §5.1）：dependencies[].vulnerabilities[] → 依赖类 RawFinding。
 *  engineRuleId = cveId = vulnerability.name（DC JSON 中 CVE 位于 vulnerabilities[].name）；
 *  filePath = 依赖 filePath（缺省 target 根，保持 NOT NULL）；packageName/packageVersion 自 packages[].id
 *  （pkg:type/group:artifact@version）推断，无则 fileName 兜底（P3-D8 both-or-neither 保证）；
 *  fixedVersion 恒 null（DC 无修复版本字段）；cvssScore = CVSSv3.baseScore 或 CVSSv2.score。 */
@Component
class DependencyCheckResultParser {
    private val objectMapper = ObjectMapper()

    fun parse(report: String): List<RawFinding> {
        val root = runCatching { objectMapper.readTree(report) }.getOrNull() ?: return emptyList()
        val deps = root.path("dependencies")
        if (!deps.isArray) return emptyList()
        val out = mutableListOf<RawFinding>()
        for (dep in deps) {
            val filePath = dep.path("filePath").takeIf { !it.isMissingNode }?.asText() ?: continue
            val packageName = packageNameOf(dep) ?: filePath     // 兜底保非空（P3-D8）
            val packageVersion = packageVersionOf(dep)
            val vulns = dep.path("vulnerabilities")
            if (!vulns.isArray) continue
            for (v in vulns) {
                val cve = v.path("name").asText("")
                if (cve.isEmpty()) continue
                out += RawFinding(
                    engineRuleId = cve,
                    ruleName = null,
                    filePath = filePath,
                    line = null,
                    severity = v.path("severity").asText("UNKNOWN"),
                    message = v.path("description").takeIf { !it.isMissingNode }?.asText(),
                    codeSnippet = null,
                    category = null,
                    packageName = packageName,
                    packageVersion = packageVersion,
                    fixedVersion = null,
                    cveId = cve,
                    cvssScore = cvssScoreOf(v),
                )
            }
        }
        return out
    }

    /** packages[].id（pkg:type/group:artifact@version）→ artifact；无则 fileName。 */
    private fun packageNameOf(dep: JsonNode): String? {
        dep.path("packages").takeIf { it.isArray && !it.isEmpty }?.let { pkgs ->
            val id = pkgs.first().path("id").asText("")
            if (id.isNotBlank()) {
                val artifact = id.substringAfterLast("/").substringBeforeLast("@")
                if (artifact.isNotBlank()) return artifact
            }
        }
        return dep.path("fileName").takeIf { !it.isMissingNode && it.asText().isNotBlank() }?.asText()
    }

    private fun packageVersionOf(dep: JsonNode): String? {
        dep.path("packages").takeIf { it.isArray && !it.isEmpty }?.let { pkgs ->
            val id = pkgs.first().path("id").asText("")
            if (id.isNotBlank()) return id.substringAfterLast("@").ifBlank { null }
        }
        return null
    }

    private fun cvssScoreOf(v: JsonNode): Double? {
        v.path("cvssv3").path("baseScore").takeIf { it.isNumber }?.let { return it.asDouble() }
        v.path("cvssv2").path("score").takeIf { it.isNumber }?.let { return it.asDouble() }
        return null
    }
}
```

`DependencyCheckSeverityMapper.kt`：

```kotlin
package com.example.compliance.engineadapter.dependencycheck

import org.springframework.stereotype.Component

/** Dependency-Check 原生 severity（spec §5.1：HIGH/MEDIUM/LOW 直通，else→MEDIUM）。
 *  注意：DC 的 CRITICAL（若本机库报告）落入 else→MEDIUM——spec 明文仅三档直通（R-M14-1 携终审留意）。 */
@Component
class DependencyCheckSeverityMapper {
    fun map(engineSeverity: String): String = when (engineSeverity.uppercase()) {
        "HIGH" -> "HIGH"
        "MEDIUM" -> "MEDIUM"
        "LOW" -> "LOW"
        else -> "MEDIUM"
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :module-engine-adapter:test --tests "*DependencyCheck*"`
Expected: 全绿（parser 2 用例 + mapper 2 用例）。

- [ ] **Step 5: 写适配器失败测试**

`DependencyCheckAdapterTest.kt`（镜像 `TrivyAdapterTest`）：

```kotlin
package com.example.compliance.engineadapter.dependencycheck

import com.example.compliance.result.engine.ScanContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyCheckAdapterTest {
    private val cli = mockk<DependencyCheckCli>()
    private val adapter = DependencyCheckAdapter(cli, DependencyCheckResultParser(), DependencyCheckSeverityMapper())

    private val report = javaClass.getResource("/dependencycheck/basic.json").readText(StandardCharsets.UTF_8)
    private val ctx = ScanContext(1L, 1L, "https://git.example.com/repo.git", "main")
    private val failCtx = ScanContext(101L, 1L, "https://git.example.com/repo.git", "main")

    @Test
    fun `execute collect normalize keeps dependency fields and maps severity`() {
        every { cli.run(any(), any()) } returns report
        val execution = adapter.executeScan(ctx)
        assertTrue(execution.success)
        assertTrue(execution.stdoutRef != null)
        val raw = adapter.collectResult(ctx)
        assertEquals(2, raw.size)
        assertEquals("High", raw[0].severity)          // 原生透传
        assertEquals("lodash", raw[0].packageName)
        val normalized = adapter.normalizeResult(ctx, raw)
        assertEquals("HIGH", normalized[0].severity)   // High → HIGH
        assertEquals("MEDIUM", normalized[1].severity) // Medium → MEDIUM
        assertEquals("lodash", normalized[0].packageName)  // 依赖字段原样保留
    }

    @Test
    fun `cli failure maps to unsuccessful execution without stdout file`() {
        every { cli.run(any(), any()) } throws IllegalStateException("dependency-check exited with code 1")
        val execution = adapter.executeScan(failCtx)
        assertTrue(!execution.success)
        assertTrue(execution.stdoutRef == null)        // F1: cli 失败不落盘 stdout
        assertTrue(adapter.collectResult(failCtx).isEmpty())
    }

    @AfterEach
    fun tearDown() {
        adapter.cleanup(ctx)
        adapter.cleanup(failCtx)
    }

    @Test
    fun `cleanup clears stdout file`() {
        every { cli.run(any(), any()) } returns report
        adapter.executeScan(ctx)
        adapter.collectResult(ctx)
        adapter.cleanup(ctx)
        assertTrue(adapter.collectResult(ctx).isEmpty())
    }

    @Test
    fun `engine name is DEPENDENCYCHECK`() {
        assertEquals("DEPENDENCYCHECK", adapter.engine)
    }
}
```

- [ ] **Step 6: 实现 CLI + 适配器**

`DependencyCheckCli.kt`：

```kotlin
package com.example.compliance.engineadapter.dependencycheck

import com.example.compliance.engineadapter.cli.CliExecutor
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files

interface DependencyCheckCli {
    fun run(targetPath: String, scanTaskId: Long): String
}

/** Dependency-Check（OWASP）CLI（spec §5.1）：--format JSON --out 目录写 dependency-check-report.json，
 *  --noupdate 避免每次联网拉 NVD。薄壳：resultFile = --out 目录下报告文件（CliExecutor 读后清理，镜像 Gitleaks resultFile 模式）；
 *  报告 JSON 作为 run 返回值（镜像 Trivy stdout 语义）。 */
@Component
class ProcessDependencyCheckCli(
    @Value("\${app.dependencycheck.timeout-seconds:600}")
    private val timeoutSeconds: Long,
) : DependencyCheckCli {
    private val executor = CliExecutor(timeoutSeconds)

    override fun run(targetPath: String, scanTaskId: Long): String {
        val outDir = Files.createTempDirectory("dependencycheck-out-").toFile()
        val report = File(outDir, "dependency-check-report.json")
        return try {
            executor.run(
                command = listOf("dependency-check", "--project", scanTaskId.toString(),
                    "--scan", targetPath, "--format", "JSON", "--out", outDir.absolutePath, "--noupdate"),
                label = "dependency-check",
                config = CliExecutor.Config(
                    mergeErrorStream = false,
                    successExitCodes = setOf(0),
                    resultFile = report,
                ),
            )
        } finally {
            // CliExecutor 的 finally 已删 resultFile；此处清空 outDir（R-M14-2；若 DC 额外写文件则 delete 失败静默，可接受）
            runCatching { outDir.delete() }
        }
    }
}
```

`DependencyCheckAdapter.kt`（五方法镜像 `TrivyAdapter`，`engine = "DEPENDENCYCHECK"`，`stdoutFile = "dependencycheck-stdout-${scanTaskId}.json"`，`scanTarget = workDir ?: repoUrl`）：

```kotlin
package com.example.compliance.engineadapter.dependencycheck

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanExecutionResult
import org.springframework.stereotype.Component
import java.io.File

/** Dependency-Check 适配器（spec §5.1）：五方法镜像 TrivyAdapter；报告经 cli 返回 → stdout 临时文件落盘。 */
@Component
class DependencyCheckAdapter(
    private val cli: DependencyCheckCli,
    private val parser: DependencyCheckResultParser,
    private val severityMapper: DependencyCheckSeverityMapper,
) : ScanEngineAdapter {

    override val engine: String = "DEPENDENCYCHECK"

    override fun prepareScan(context: ScanContext) {}

    override fun executeScan(context: ScanContext): ScanExecutionResult {
        val target = scanTarget(context)
        val start = System.currentTimeMillis()
        return runCatching {
            val stdout = cli.run(target, context.scanTaskId)
            val file = stdoutFile(context)
            file.writeText(stdout)
            ScanExecutionResult(success = true, stdoutRef = file.absolutePath)
        }.getOrElse { e ->
            // F1（spec 2.1）: cli 失败（非 0 退出 / 超时）→ success=false，不落盘 stdout（绝不产出假干净扫描）
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
        File(System.getProperty("java.io.tmpdir"), "dependencycheck-stdout-${context.scanTaskId}.json")

    private fun scanTarget(context: ScanContext): String = context.workDir ?: context.repoUrl
}
```

- [ ] **Step 7: 运行全模块测试**

Run: `./gradlew :module-engine-adapter:test`
Expected: 全绿（既有 30 + 新增 8：parser 2 + mapper 2 + adapter 4）。

- [ ] **Step 8: 提交**

```bash
git add module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/dependencycheck/ module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/dependencycheck/ module-engine-adapter/src/test/resources/dependencycheck/
git commit -m "feat(engine-adapter): OWASP Dependency-Check engine (cli/parser/mapper/adapter) (m14)"
```

---

### Task 14.2: Detekt 引擎（Kotlin 静态分析，代码类）

**Files:**
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/detekt/DetektCli.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/detekt/DetektResultParser.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/detekt/DetektSeverityMapper.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/detekt/DetektAdapter.kt`
- Test: `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/detekt/DetektResultParserTest.kt`
- Test: `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/detekt/DetektSeverityMapperTest.kt`
- Test: `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/detekt/DetektAdapterTest.kt`
- Fixture: `module-engine-adapter/src/test/resources/detekt/sarif.json`

**Interfaces:**
- Produces: `DetektCli.run(targetPath: String): String`（返回 SARIF 内容）；`DetektResultParser.parse(sarif: String): List<RawFinding>`；`DetektSeverityMapper.map(engineSeverity: String): String`；`DetektAdapter : ScanEngineAdapter`（`engine = "DETEKT"`）。
- Consumes: `RawFinding` 8 代码字段（依赖字段恒 null）；`CliExecutor.run` resultFile 模式；`@Value("\${app.detekt.timeout-seconds:300}")`。

- [ ] **Step 1: 写失败测试 + fixture**

`src/test/resources/detekt/sarif.json`（SARIF 2.1.0 形态：`runs[].results[]`）：

```json
{
  "version": "2.1.0",
  "$schema": "https://json.schemastore.org/sarif-2.1.0.json",
  "runs": [
    {
      "tool": { "driver": { "name": "detekt", "version": "1.23.7" } },
      "results": [
        {
          "ruleId": "MagicNumber",
          "level": "warning",
          "message": { "text": "This expression contains a magic number" },
          "locations": [
            {
              "physicalLocation": {
                "artifactLocation": { "uri": "src/main/kotlin/com/example/App.kt" },
                "region": { "startLine": 17 }
              }
            }
          ]
        },
        {
          "ruleId": "LongMethod",
          "level": "error",
          "message": { "text": "The function is too long" },
          "locations": [
            {
              "physicalLocation": {
                "artifactLocation": { "uri": "src/main/kotlin/com/example/Service.kt" },
                "region": { "startLine": 42 }
              }
            }
          ]
        }
      ]
    }
  ]
}
```

`DetektResultParserTest.kt`：

```kotlin
package com.example.compliance.engineadapter.detekt

import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetektResultParserTest {
    private val parser = DetektResultParser()
    private val sarif = javaClass.getResource("/detekt/sarif.json").readText(StandardCharsets.UTF_8)

    @Test
    fun `parses sarif results into code-class findings`() {
        val out = parser.parse(sarif)
        assertEquals(2, out.size)
        val first = out[0]
        assertEquals("MagicNumber", first.engineRuleId)
        assertEquals("src/main/kotlin/com/example/App.kt", first.filePath)
        assertEquals(17, first.line)
        assertEquals("warning", first.severity)          // 原生透传（映射在 normalizeResult）
        assertEquals("This expression contains a magic number", first.message)
        assertEquals("MagicNumber", first.category)      // ruleId 点前缀段
        assertNull(first.packageName)                    // 代码类恒无依赖字段
        val second = out[1]
        assertEquals("LongMethod", second.engineRuleId)
        assertEquals(42, second.line)
        assertEquals("error", second.severity)
    }

    @Test
    fun `invalid or empty input yields empty list`() {
        assertTrue(parser.parse("not json").isEmpty())
        assertTrue(parser.parse("{}").isEmpty())
    }
}
```

`DetektSeverityMapperTest.kt`：

```kotlin
package com.example.compliance.engineadapter.detekt

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DetektSeverityMapperTest {
    private val mapper = DetektSeverityMapper()

    @Test
    fun `maps native detekt severities`() {
        assertEquals("HIGH", mapper.map("error"))
        assertEquals("MEDIUM", mapper.map("warning"))
        assertEquals("LOW", mapper.map("info"))
    }

    @Test
    fun `maps unknown severities to LOW`() {
        assertEquals("LOW", mapper.map("UNKNOWN"))
        assertEquals("LOW", mapper.map("note"))
        assertEquals("LOW", mapper.map(""))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :module-engine-adapter:compileTestKotlin`
Expected: FAIL——`DetektResultParser`/`DetektSeverityMapper` 未定义。

- [ ] **Step 3: 实现 parser + mapper**

`DetektResultParser.kt`：

```kotlin
package com.example.compliance.engineadapter.detekt

import com.example.compliance.result.engine.RawFinding
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/** Detekt SARIF 解析（spec §5.2）：runs[].results[] → 代码类 RawFinding（无依赖字段，恒 null）。
 *  engineRuleId = ruleId；filePath = locations[0].physicalLocation.artifactLocation.uri；
 *  line = region.startLine；severity 原生透传（error/warning/note）；message = message.text；
 *  category = ruleId 点前缀段。 */
@Component
class DetektResultParser {
    private val objectMapper = ObjectMapper()

    fun parse(sarif: String): List<RawFinding> {
        val root = runCatching { objectMapper.readTree(sarif) }.getOrNull() ?: return emptyList()
        val runs = root.path("runs")
        if (!runs.isArray) return emptyList()
        val out = mutableListOf<RawFinding>()
        for (run in runs) {
            val results = run.path("results")
            if (!results.isArray) continue
            for (r in results) {
                val ruleId = r.path("ruleId").asText("")
                if (ruleId.isEmpty()) continue
                val loc = r.path("locations").takeIf { it.isArray && !it.isEmpty }?.get(0)
                    ?.path("physicalLocation")
                out += RawFinding(
                    engineRuleId = ruleId,
                    ruleName = null,
                    filePath = loc?.path("artifactLocation")?.path("uri")?.asText("") ?: "",
                    line = loc?.path("region")?.path("startLine")?.takeIf { it.isNumber }?.asInt(),
                    severity = r.path("level").asText("warning"),
                    message = r.path("message").path("text").takeIf { !it.isMissingNode }?.asText(),
                    codeSnippet = null,
                    category = ruleId.substringBefore(".").ifBlank { null },
                )
            }
        }
        return out
    }
}
```

`DetektSeverityMapper.kt`：

```kotlin
package com.example.compliance.engineadapter.detekt

import org.springframework.stereotype.Component

/** Detekt 原生 severity（error/warning/info/note）→ 统一等级（镜像 SemgrepSeverityMapper；spec §5.2 代码类）。 */
@Component
class DetektSeverityMapper {
    fun map(engineSeverity: String): String = when (engineSeverity.uppercase()) {
        "ERROR" -> "HIGH"
        "WARNING" -> "MEDIUM"
        "INFO" -> "LOW"
        else -> "LOW"
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :module-engine-adapter:test --tests "*Detekt*"`
Expected: 全绿（parser 2 + mapper 2）。

- [ ] **Step 5: 写适配器失败测试**

`DetektAdapterTest.kt`（镜像 `SemgrepAdapterTest`；注意 `cli.run(target)` 单参——Detekt 无 ref/baseline）：

```kotlin
package com.example.compliance.engineadapter.detekt

import com.example.compliance.result.engine.ScanContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetektAdapterTest {
    private val cli = mockk<DetektCli>()
    private val adapter = DetektAdapter(cli, DetektResultParser(), DetektSeverityMapper())

    private val sarif = javaClass.getResource("/detekt/sarif.json").readText(StandardCharsets.UTF_8)
    private val ctx = ScanContext(1L, 1L, "https://git.example.com/repo.git", "main")
    private val failCtx = ScanContext(101L, 1L, "https://git.example.com/repo.git", "main")

    @Test
    fun `execute collect normalize maps severity and keeps code fields`() {
        every { cli.run(any()) } returns sarif
        val execution = adapter.executeScan(ctx)
        assertTrue(execution.success)
        assertTrue(execution.stdoutRef != null)
        val raw = adapter.collectResult(ctx)
        assertEquals(2, raw.size)
        assertEquals("warning", raw[0].severity)
        val normalized = adapter.normalizeResult(ctx, raw)
        assertEquals("MEDIUM", normalized[0].severity)   // warning → MEDIUM
        assertEquals("HIGH", normalized[1].severity)     // error → HIGH
        assertEquals("src/main/kotlin/com/example/App.kt", normalized[0].filePath)
    }

    @Test
    fun `cli failure maps to unsuccessful execution without stdout file`() {
        every { cli.run(any()) } throws IllegalStateException("detekt exited with code 1")
        val execution = adapter.executeScan(failCtx)
        assertTrue(!execution.success)
        assertTrue(execution.stdoutRef == null)          // F1: cli 失败不落盘 stdout
        assertTrue(adapter.collectResult(failCtx).isEmpty())
    }

    @AfterEach
    fun tearDown() {
        adapter.cleanup(ctx)
        adapter.cleanup(failCtx)
    }

    @Test
    fun `cleanup clears stdout file`() {
        every { cli.run(any()) } returns sarif
        adapter.executeScan(ctx)
        adapter.collectResult(ctx)
        adapter.cleanup(ctx)
        assertTrue(adapter.collectResult(ctx).isEmpty())
    }

    @Test
    fun `engine name is DETEKT`() {
        assertEquals("DETEKT", adapter.engine)
    }
}
```

- [ ] **Step 6: 实现 CLI + 适配器**

`DetektCli.kt`：

```kotlin
package com.example.compliance.engineadapter.detekt

import com.example.compliance.engineadapter.cli.CliExecutor
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File

interface DetektCli {
    fun run(targetPath: String): String
}

/** Detekt Kotlin 静态分析 CLI（spec §5.2）：--report sarif:<file> 写 SARIF JSON（镜像 Gitleaks resultFile 模式）。 */
@Component
class ProcessDetektCli(
    @Value("\${app.detekt.timeout-seconds:300}")
    private val timeoutSeconds: Long,
) : DetektCli {
    private val executor = CliExecutor(timeoutSeconds)

    override fun run(targetPath: String): String {
        val report = File.createTempFile("detekt-report-", ".sarif")
        return executor.run(
            command = listOf("detekt", "--input", targetPath, "--report", "sarif:${report.absolutePath}"),
            label = "detekt",
            config = CliExecutor.Config(
                mergeErrorStream = false,
                successExitCodes = setOf(0),
                resultFile = report,
            ),
        )
    }
}
```

`DetektAdapter.kt`（五方法镜像 `SemgrepAdapter`；`engine = "DETEKT"`；`stdoutFile = "detekt-stdout-${scanTaskId}.sarif"`；`scanTarget = workDir ?: repoUrl`；`cli.run(target)` 单参）：

```kotlin
package com.example.compliance.engineadapter.detekt

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanExecutionResult
import org.springframework.stereotype.Component
import java.io.File

/** Detekt 适配器（spec §5.2）：五方法镜像 SemgrepAdapter（代码类，无依赖字段）。 */
@Component
class DetektAdapter(
    private val cli: DetektCli,
    private val parser: DetektResultParser,
    private val severityMapper: DetektSeverityMapper,
) : ScanEngineAdapter {

    override val engine: String = "DETEKT"

    override fun prepareScan(context: ScanContext) {}

    override fun executeScan(context: ScanContext): ScanExecutionResult {
        val target = scanTarget(context)
        val start = System.currentTimeMillis()
        return runCatching {
            val stdout = cli.run(target)
            val file = stdoutFile(context)
            file.writeText(stdout)
            ScanExecutionResult(success = true, stdoutRef = file.absolutePath)
        }.getOrElse { e ->
            // F1（spec 2.1）: cli 失败（非 0 退出 / 超时）→ success=false，不落盘 stdout（绝不产出假干净扫描）
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
        File(System.getProperty("java.io.tmpdir"), "detekt-stdout-${context.scanTaskId}.sarif")

    private fun scanTarget(context: ScanContext): String = context.workDir ?: context.repoUrl
}
```

- [ ] **Step 7: 运行全模块测试**

Run: `./gradlew :module-engine-adapter:test`
Expected: 全绿（既有 38 + 新增 8：parser 2 + mapper 2 + adapter 4）。

- [ ] **Step 8: 提交**

```bash
git add module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/detekt/ module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/detekt/ module-engine-adapter/src/test/resources/detekt/
git commit -m "feat(engine-adapter): Detekt engine (cli/parser/mapper/adapter) (m14)"
```

---

### Task 14.3: M14 集成测试 + 配置

**Files:**
- Create: `app-server/src/test/kotlin/com/example/compliance/scan/M14EngineIntegrationTest.kt`
- Modify: `app-server/src/main/resources/application.yml:41-44`（checkout-engines + 2 timeout keys）

**Interfaces:**
- Consumes: `ProjectService.create(CreateProjectCommand(code,name,desc,ownerUserId))`、`bindRepository(projectId, BindRepositoryCommand(code, gitUrl, type, branch, token))`；`RuleService.create/addEngineBinding/setEvaluationPolicy/publish`；`ScanTaskService.startScan(projectId, engine, ref)`；`FindingLifecyclePort.findingsForScanTask(scanTaskId)`；`@Value("\${app.scan.checkout-engines}")`。引擎 `DEPENDENCYCHECK`/`DETEKT` 加入 checkout-engines。

- [ ] **Step 1: 修改配置**

`app-server/src/main/resources/application.yml`——在第 40-44 行（trivy 块后）追加：

```yaml
  dependencycheck:
    timeout-seconds: 600   # spec §5.1：DC 依赖库大、--noupdate 仍慢，默认 600
  detekt:
    timeout-seconds: 300   # R-M14-3：spec 未定名，镜像 semgrep/gitleaks 代码类引擎
```

并把第 44 行 `checkout-engines: SEMGREP,GITLEAKS,TRIVY   # M11：GITLEAKS/TRIVY 都扫检出目录 → 触发 GitCheckout` 改为：

```yaml
    checkout-engines: SEMGREP,GITLEAKS,TRIVY,DEPENDENCYCHECK,DETEKT   # M11/M14：真实引擎都扫检出目录 → 触发 GitCheckout
```

（仅加 timeout keys——不加 command keys：既有 semgrep/gitleaks/trivy 的 command 键是未使用装饰，Process*Cli 硬编码命令名，R-M14-5 YAGNI。）

- [ ] **Step 2: 写集成测试**

`app-server/src/test/kotlin/com/example/compliance/scan/M14EngineIntegrationTest.kt`：

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

/** M14 引擎契约集成测试（镜像 M11）：STUBDC/STUBDET 五方法接入 + 依赖/代码类 finding 端到端落库 + checkout-engines 配置断言。
 *  数据前缀 M14-*；STUBDC/STUBDET 不在 checkout-engines → commitId null。
 *  桩引擎返回归一化 severity（RawFinding 契约：severity 已归一化，非原生值）。 */
class M14EngineIntegrationTest : AbstractIntegrationTest() {

    object StubState {
        @Volatile var prepared = false
        @Volatile var executed = false
        @Volatile var collected = false
        @Volatile var cleanupCalled = false
    }

    @TestConfiguration
    class StubAdaptersConfig {
        @Bean
        fun stubDcAdapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUBDC"
            override fun prepareScan(context: ScanContext) { StubState.prepared = true }
            override fun executeScan(context: ScanContext): ScanExecutionResult {
                StubState.executed = true
                return ScanExecutionResult(success = true, durationMs = 5)
            }
            override fun collectResult(context: ScanContext): List<RawFinding> {
                StubState.collected = true
                return listOf(RawFinding(
                    "stub-dc-cve-1", "M14 DependencyCheck", "package-lock.json", null, "HIGH",
                    "command injection in lodash", null, null,
                    "lodash", "4.17.20", null, "CVE-2021-23337", 9.8,
                ))
            }
            override fun normalizeResult(context: ScanContext, raw: List<RawFinding>): List<RawFinding> = raw
            override fun cleanup(context: ScanContext) { StubState.cleanupCalled = true }
        }

        @Bean
        fun stubDetektAdapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUBDET"
            override fun prepareScan(context: ScanContext) { StubState.prepared = true }
            override fun executeScan(context: ScanContext): ScanExecutionResult {
                StubState.executed = true
                return ScanExecutionResult(success = true, durationMs = 5)
            }
            override fun collectResult(context: ScanContext): List<RawFinding> {
                StubState.collected = true
                return listOf(RawFinding(
                    "stub-detekt-rule", "M14 Detekt", "src/main/kotlin/com/example/App.kt", 17, "MEDIUM",
                    "magic number",
                ))
            }
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
    fun `dependencycheck-named stub persists dependency finding with five fields end to end`() {
        val project = projectService.create(CreateProjectCommand("M14DC", "M14 dependencycheck", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("m14dc-repo", "https://git.example.com/m14dc.git", "GITLAB", "main", "tok"))
        val rule = ruleService.create(CreateRuleCommand("M14-DC", "M14 依赖漏洞", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUBDC", "stub-dc-cve-1", null))
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'HIGH'"))
        ruleService.publish(rule.id!!)

        val task = scanTaskService.startScan(project.id!!, "STUBDC", "main")
        var done = false
        repeat(50) {
            val s = scanTaskService.get(task.id!!).status
            if (s != ScanTaskStatus.PENDING && s != ScanTaskStatus.PREPARING && s != ScanTaskStatus.RUNNING) { done = true; return@repeat }
            Thread.sleep(200)
        }
        assertTrue(done, "scan should finish within timeout")
        assertEquals(ScanTaskStatus.SUCCESS, scanTaskService.get(task.id!!).status)
        assertNull(scanTaskService.get(task.id!!).commitId, "STUBDC not in checkout-engines -> commitId null")

        val views = lifecyclePort.findingsForScanTask(task.id!!)
        assertEquals(1, views.size)
        val v = views[0]
        assertEquals("M14-DC", v.ruleCode)
        assertEquals("STUBDC", v.engine)
        assertEquals("lodash", v.packageName)
        assertEquals("4.17.20", v.packageVersion)
        assertNull(v.fixedVersion)                          // DC 无修复版本字段（spec §5.1）
        assertEquals("CVE-2021-23337", v.cveId)
        assertEquals(9.8, v.cvssScore)
    }

    @Test
    fun `detekt-named stub persists code-class finding without dependency fields end to end`() {
        val project = projectService.create(CreateProjectCommand("M14DT", "M14 detekt", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("m14dt-repo", "https://git.example.com/m14dt.git", "GITLAB", "main", "tok"))
        val rule = ruleService.create(CreateRuleCommand("M14-DET", "M14 静态分析", "MEDIUM", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUBDET", "stub-detekt-rule", null))
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'MEDIUM'"))
        ruleService.publish(rule.id!!)

        val task = scanTaskService.startScan(project.id!!, "STUBDET", "main")
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
        assertEquals("M14-DET", v.ruleCode)
        assertEquals("STUBDET", v.engine)
        assertEquals("src/main/kotlin/com/example/App.kt", v.filePath)
        assertEquals(17, v.lineNumber)      // FindingView 暴露 lineNumber（非 line；R-M14-7 修正）
        assertNull(v.packageName, "code-class finding has no dependency fields")
    }

    @Test
    fun `checkout-engines config contains real engines including new ones`() {
        assertTrue("SEMGREP" in checkoutEngines)
        assertTrue("GITLEAKS" in checkoutEngines)
        assertTrue("TRIVY" in checkoutEngines)
        assertTrue("DEPENDENCYCHECK" in checkoutEngines)
        assertTrue("DETEKT" in checkoutEngines)
    }
}
```

- [ ] **Step 3: 运行确认**

Run: `./gradlew :app-server:test --tests "*M14EngineIntegrationTest*"`
Expected: 3/3 绿（DC e2e + Detekt e2e + checkout-engines 断言；共享 Testcontainers PG）。

- [ ] **Step 4: 全量 build 验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（全量绿；既有 M11 e2e 的 checkout-engines 断言是 membership 断言，不受加引擎影响）。

- [ ] **Step 5: 提交**

```bash
git add app-server/src/test/kotlin/com/example/compliance/scan/M14EngineIntegrationTest.kt app-server/src/main/resources/application.yml
git commit -m "test(app-server): M14 stub-engine E2E for dependency-check/detekt + checkout-engines config (m14)"
```

---

## Self-Review（写计划时完成）

**Spec 覆盖：**
- §5.1 Dependency-Check（CLI/解析/契约/severity/checkout-engines/both-or-neither）→ Task 14.1 ✓
- §5.2 Detekt（CLI/解析/契约/severity/checkout-engines/代码类分流）→ Task 14.2 ✓
- §5.3 集成测试（STUBDC/STUBDET、FAIL、poll、commitId null、M14-*、fixture+三测试类、@AfterEach 清理、全量 build）→ Task 14.3 ✓

**Placeholder scan：** 无 TBD/TODO；每步含完整代码 + fixture 全量 JSON。

**Type 一致性：**
- `DependencyCheckCli.run(targetPath, scanTaskId: Long)` ↔ adapter `cli.run(target, context.scanTaskId)`（ScanContext.scanTaskId: Long）✓
- `RawFinding` 13 位置参数：STUBDC `RawFinding("stub-dc-cve-1","M14 DependencyCheck","package-lock.json",null,"HIGH","command injection in lodash",null,null,"lodash","4.17.20",null,"CVE-2021-23337",9.8)` ↔ 13.1 依赖分流（8 代码 + 5 依赖尾部）✓；STUBDET 6 位置参数代码类 ✓
- `FindingView`：`fixedVersion: String?`（assertNull）、`cvssScore: Double?`（assertEquals(9.8)）、`filePath/line`（M11/13.3 已证实暴露）✓
- `ScanContext(1L,1L,"https://...","main")` 4 位置参数 ↔ TrivyAdapterTest 同形 ✓
- `CliExecutor.Config(mergeErrorStream, successExitCodes, resultFile, includeStdoutTail)` 命名参数逐字 ✓

**Rulings（写计划时固化）：**
- **R-M14-1**（DC severity 映射）：`HIGH/MEDIUM/LOW` 直通，`else→MEDIUM`（spec §5.1 明文）。**DC 的 CRITICAL（若报告）落入 MEDIUM**——spec 明文仅三档直通，按 spec 逐字执行，携终审留意。—— why：spec 是权威，明文只列三档。—— cost if wrong：本地 DC 库若报 Critical 会降级为 MEDIUM，一行 mapper 可改。
- **R-M14-2**（DC --out 目录清理）：CliExecutor finally 删 resultFile（报告文件），CLI finally 删 outDir（空目录）。—— why：不泄漏临时目录；DC 额外写文件时 delete 失败静默可接受。—— cost if wrong：极端情形留空目录，无害。
- **R-M14-3**（detekt timeout key）：`app.detekt.timeout-seconds: 300`（spec §5.2 未定名）。—— why：镜像 semgrep/gitleaks 代码类引擎（300）。—— cost if wrong：无。
- **R-M14-4**（DC packageName 兜底链）：`packages[].id` → `fileName` → `filePath`（保非空）。—— why：P3-D8 both-or-neither 要求恒设 packageName+cveId，兜底防守卫抛错。—— cost if wrong：极端缺字段时 packageName 退化为路径，可接受（DC 恒有 fileName）。
- **R-M14-5**（配置最小化）：只加 timeout keys，不加 command keys（既有 command 键是未使用装饰）。—— why：YAGNI，Process*Cli 硬编码命令名。—— cost if wrong：无。
- **R-M14-6**（桩 severity 归一化）：STUBDET 桩返回 "MEDIUM"（非原生 "WARNING"）——RawFinding 契约 severity 已归一化。—— why：镜像 M11 STUBG/STUBT（返回 HIGH/CRITICAL 归一化值）。—— cost if wrong：桩若返回原生值，FindingService 收到非枚举 severity，破坏契约。

**Pre-flight conflict scan：**

| 任务对 | 生产 vs 消费 | 结论 |
|---|---|---|
| 14.1 → 14.3 | `DependencyCheckAdapter.engine="DEPENDENCYCHECK"`（真实引擎）vs STUBDC 桩（内联独立） | 无冲突；e2e 用桩不用真实适配器 |
| 14.2 → 14.3 | `DetektAdapter.engine="DETEKT"` vs STUBDET 桩 | 无冲突 |
| 14.1 ↔ 自身 | CLI run 签名 / parser 字段名（name/filePath/packages/cvssv3/cvssv2） vs fixture / 测试断言 | 逐字一致（fixture 字段路径 = parser 读取路径） |
| 14.2 ↔ 自身 | SARIF 字段路径（runs/results/ruleId/locations/artifactLocation/region/level/message.text） vs fixture | 逐字一致 |
| 14.1/14.2 → M13 CliExecutor | resultFile 模式（Gitleaks 先例）复用 | CliExecutor 已支持，无冲突 |
| 14.3 → application.yml | checkout-engines += DEPENDENCYCHECK/DETEKT vs 既有 M11 `assertTrue("SEMGREP" in checkoutEngines)` | membership 断言，加引擎不破坏（非相等断言） |
| 14.3 → M13 P3-D8 | STUBDC 恒设 packageName+cveId、STUBDET 恒不设 | both-set/both-null 均通过守卫 |
| 14.3 → 既有 e2e | M14-* 前缀 vs M11-*/M12-*/M13-* | 无交叉 |
| 14.1/14.2 → 全量 build | 新 @Component 自动注册入 EngineAdapterRegistry（`DefaultEngineAdapterRegistry(List<ScanEngineAdapter>)`） | 自动，无冲突（M13 已验证 registry 自动注册模式） |
