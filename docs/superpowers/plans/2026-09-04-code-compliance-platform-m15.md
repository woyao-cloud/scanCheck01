# M15 SonarQube 引擎接入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 接入第 6 个扫描引擎 `SONARQUBE`（首个服务端型引擎）——`sonar-scanner` 提交分析 → `executeScan` 内轮询 CE task → 拉取 issue → 标准化为代码类 RawFinding，复用 M3 凭证加密。

**Architecture:** 五方法 `ScanEngineAdapter` 契约不变。编排器（module-scan）解密 `Repository.credentialRef` 注入 `ScanContext.credentialToken`（尾部默认值，零破坏）；`SonarQubeAdapter`（module-engine-adapter）在 `executeScan` 内完成「跑 scanner → 提取 CE taskId → 轮询 `/api/ce/task` → 拉取 `/api/issues/search` 落盘 stdout 文件」三段，`collectResult` 纯本地解析。token 经 `CliExecutor.Config.env`（`SONAR_TOKEN` 环境变量）传递，绝不进进程 argv。

**Tech Stack:** Kotlin 2.0.21 / Spring Boot 3.3.5 BOM；Spring `RestClient`（spring-web，BOM 管版本，module-engine-adapter 增 `implementation("org.springframework:spring-web")`）；MockRestServiceServer（spring-test，已在 convention plugin test 依赖）；JUnit 5 + MockK（既有）。

**Spec:** `docs/superpowers/specs/2026-09-04-code-compliance-platform-m15-design.md`（本计划从 spec 论证；执行者须同时读 spec 与计划，冲突以 spec 为权威）

## Global Constraints

- **模块依赖**：module-engine-adapter 只增 `implementation("org.springframework:spring-web")`（BOM 3.3.5 管版本）；不新增对 module-project 的依赖（凭证经编排器注入，不经 adapter 直取）。
- **契约扩展（非破坏）**：`ScanContext.credentialToken: String?` 追加在参数末尾带默认值；`CliExecutor.Config.env: Map<String,String> = emptyMap()` 追加默认；五方法 `ScanEngineAdapter` 契约不变。既有命名参数调用点零改动。
- **P3-D8**：SonarQube 代码类引擎——RawFinding 仅 8 代码字段，5 依赖字段恒 null（both-null，`packageName`/`cveId` 等恒不设）。
- **severity 归一化**：parser 原生透传，normalizeResult 映射。STUB 桩返回归一化值（RawFinding 契约）。
- **severity 映射表（R-M15-D6）**：BLOCKER→CRITICAL、CRITICAL→HIGH、MAJOR→MEDIUM、MINOR→LOW、INFO→LOW、else→LOW。
- **executeScan F1 语义**：任一失败 → `success=false`，不落盘 stdout 文件（绝不产出假干净扫描）。成功 → issue JSON 写 `sonarqube-stdout-<scanTaskId>.json`（`java.io.tmpdir`），`collectResult` 纯本地解析。
- **checkout-engines 门控**：SONARQUBE ∈ 列表 → GitCheckout；STUBSONAR ∉ 列表 → commitId null。
- **CE 轮询**：间隔 5s（`pollIntervalMs` 构造参数，默认 5000，测试可缩短）；总超时 `app.sonarqube.timeout-seconds: 900`；SUCCESS 返回、FAILED/CANCELED 抛、超时抛。
- **taskId 提取（R-M15-D9）**：`Regex("ce/task\\?id=([A-Za-z0-9_-]+)")`；提取失败 → 扫描失败。
- **projectKey（R-M15-D4）**：`repoUrl.substringAfterLast("/").removeSuffix(".git")`。
- **共享 Testcontainers**：max_connections=300 保持（改不得）；集成测试数据前缀 `M15-*`（与既有里程碑不相交）。
- **红线**：不硬编码合规规则；历史扫描结果不可改；无 DDL（复用 credentialRef，零迁移）。
- **编排器凭证注入**：`credentialToken = repo.credentialRef?.let { runCatching { credentialCrypto.decrypt(it) }.getOrNull() }`（防御性，R-M15-D2）。
- **门控真服务器 e2e 延后（spec §3.6「可选」裁定）**：spec 列出的 `APP_SONAR_E2E` 真 SQ 服务器 e2e 本计划**不实现**——需 operator 提供 SQ 服务器 + sonar-scanner 二进制，CI 恒跳过（死重，M14 StubState-flags 教训）。真实引擎正确性由 Task 2 单测 + STUBSONAR e2e 锚定；operator 手动验证时可用 Task 2 的 ProcessSonarQubeCli + SonarQubeApiClient 组合。

---

### Task 1: 契约与管线 — CliExecutor env + ScanContext.credentialToken + 编排器凭证注入

**Files:**
- Modify: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/cli/CliExecutor.kt`（Config += env，run 内 putAll）
- Test: Create `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/cli/CliExecutorTest.kt`
- Modify: `module-result/src/main/kotlin/com/example/compliance/result/engine/ScanEngineAdapter.kt`（ScanContext += credentialToken）
- Modify: `module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanOrchestrator.kt`（注入 CredentialCrypto + context 行）

**Interfaces:**
- Consumes: 既有 `ScanContext`（module-result）、`CliExecutor`（module-engine-adapter）、`ScanOrchestrator`（module-scan，已注入 RepoRepository/GitCheckout）、`CredentialCrypto`（module-project infrastructure——module-scan 已依赖 module-project，可直接注入）、`Repository.credentialRef: String?`。
- Produces: `ScanContext(scanTaskId, projectId, repoUrl, ref, workDir, commitId, timeoutSeconds, paramsJson, configJson, credentialToken: String? = null)`；`CliExecutor.Config(mergeErrorStream, successExitCodes, resultFile = null, includeStdoutTail = false, env: Map<String, String> = emptyMap())`；`ScanOrchestrator` 构造 ScanContext 时注入 `credentialToken = repo.credentialRef?.let { runCatching { credentialCrypto.decrypt(it) }.getOrNull() }`。Task 2 的 ProcessSonarQubeCli 使用 `Config.env`；SonarQubeAdapter 读取 `context.credentialToken`；Task 3 的 STUBSONAR 桩在 prepareScan 记录 `context.credentialToken` 供断言（R-M15-D2 端到端锚定）。

- [ ] **Step 1: 写失败测试（CliExecutor env 传递）**

创建 `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/cli/CliExecutorTest.kt`：

```kotlin
package com.example.compliance.engineadapter.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** M15 (R-M15-D3)：CliExecutor 新能力 env 需锚定 —— 子进程必须读到环境变量（token 不进 argv 的前提）。 */
class CliExecutorTest {

    @Test
    fun `env map is passed to child process`() {
        val os = System.getProperty("os.name").lowercase()
        // Windows: cmd 解析 %VAR%；Unix: sh 解析 $VAR。环境未传递时输出字面量 → 断言失败（真实回归检测）。
        val command = if (os.contains("win")) {
            listOf("cmd", "/c", "echo %M15_CLI_TEST_ENV%")
        } else {
            listOf("sh", "-c", "echo \"\$M15_CLI_TEST_ENV\"")
        }
        val out = CliExecutor(10).run(
            command = command,
            label = "env-probe",
            config = CliExecutor.Config(
                mergeErrorStream = false,
                successExitCodes = setOf(0),
                env = mapOf("M15_CLI_TEST_ENV" to "hello"),
            ),
        )
        assertTrue(out.trim().contains("hello"), "child should see M15_CLI_TEST_ENV=hello, got: $out")
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :module-engine-adapter:test --tests "com.example.compliance.engineadapter.cli.CliExecutorTest"`
Expected: 编译失败 —— `Config` 无 `env` 参数（RED，编译期失败即可）。

- [ ] **Step 3: 实现 CliExecutor env 支持**

在 `CliExecutor.kt` 的 `class Config` 末尾追加参数：

```kotlin
        val includeStdoutTail: Boolean = false, // trivy=true；gitleaks=false
        val env: Map<String, String> = emptyMap(),  // M15 (R-M15-D3)：进程环境变量（SONAR_TOKEN 等），不进 argv → 不泄露于进程列表
```

在 `run` 内 `val pb = ProcessBuilder(command)` 之后追加一行：

```kotlin
            val pb = ProcessBuilder(command)
            pb.environment().putAll(config.env)  // M15 (R-M15-D3)：token 等经环境变量传入，绝不进 argv
```

- [ ] **Step 4: 运行测试验证通过**

Run: `./gradlew :module-engine-adapter:test --tests "com.example.compliance.engineadapter.cli.CliExecutorTest"`
Expected: PASS（1 test）。既有 46 个适配器测试不受影响（Config 仅追加默认参数）。

- [ ] **Step 5: ScanContext 追加 credentialToken**

在 `module-result/.../result/engine/ScanEngineAdapter.kt` 的 `data class ScanContext` 参数末尾（`configJson` 之后）追加：

```kotlin
    val configJson: String? = null,     // 兼容保留
    // M15 (R-M15-D2)：编排器解密 Repository.credentialRef 注入；SonarQube 用作 SONAR_TOKEN。追加尾部默认 → 既有位置调用点零破坏。
    val credentialToken: String? = null,
```

- [ ] **Step 6: 编排器注入 CredentialCrypto 并解密 credentialRef**

在 `module-scan/.../application/ScanOrchestrator.kt`：

(a) 新增 import（放在 `import com.example.compliance.project.infrastructure.RepoRepository` 之后，按字母序）：

```kotlin
import com.example.compliance.project.infrastructure.CredentialCrypto
```

(b) 构造器 `private val gitCheckout: GitCheckout,` 之后追加：

```kotlin
    private val credentialCrypto: CredentialCrypto,
```

(c) `context = ScanContext(...)` 构造（L84-87）替换为：

```kotlin
            context = ScanContext(
                scanTaskId = task.id!!, projectId = task.projectId, repoUrl = repo.gitUrl,
                ref = task.ref, workDir = checkout?.workDir, commitId = checkout?.commitId,
                credentialToken = repo.credentialRef?.let { runCatching { credentialCrypto.decrypt(it) }.getOrNull() },  // R-M15-D2：凭证损坏不阻断扫描（SQ 认证时显式失败）
            )
```

- [ ] **Step 7: 编译 + 全量 build 验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL —— 既有模块全绿（ScanContext 尾部默认参数 → 位置调用点零破坏；ScanOrchestrator 唯一构造点 Spring 注入 CredentialCrypto）。app-server 集成套件（M11–M14，共享 Testcontainers PG）全绿。

- [ ] **Step 8: Commit**

```bash
git add module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/cli/CliExecutor.kt \
        module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/cli/CliExecutorTest.kt \
        module-result/src/main/kotlin/com/example/compliance/result/engine/ScanEngineAdapter.kt \
        module-scan/src/main/kotlin/com/example/compliance/scan/application/ScanOrchestrator.kt
git commit -m "feat(scan): credential token into ScanContext + CliExecutor env support (m15)"
```

---

### Task 2: SonarQube 引擎（module-engine-adapter）

**Files:**
- Modify: `module-engine-adapter/build.gradle.kts`（+ spring-web）
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/sonarqube/SonarQubeCli.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/sonarqube/SonarQubeApiClient.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/sonarqube/SonarQubeResultParser.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/sonarqube/SonarQubeSeverityMapper.kt`
- Create: `module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/sonarqube/SonarQubeAdapter.kt`
- Test: Create `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/sonarqube/SonarQubeResultParserTest.kt`
- Test: Create `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/sonarqube/SonarQubeSeverityMapperTest.kt`
- Test: Create `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/sonarqube/SonarQubeApiClientTest.kt`
- Test: Create `module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/sonarqube/SonarQubeAdapterTest.kt`
- Create: `module-engine-adapter/src/test/resources/sonarqube/issues.json`（fixture）

**Interfaces:**
- Consumes: `ScanContext.credentialToken` + `CliExecutor.Config.env`（Task 1）；`ScanExecutionResult`/`RawFinding`/`ScanEngineAdapter`（module-result）；Jackson `ObjectMapper`（module-common 传递，既有 parser 同款）；`RestClient`（spring-web，本任务新增）。
- Produces: `SonarQubeCli.run(workDir: String, projectKey: String, token: String, serverUrl: String): String`；`SonarQubeApiClient.ceTaskStatus(serverUrl: String, taskId: String, token: String): String` 与 `issues(serverUrl: String, projectKey: String, token: String): String`；`SonarQubeResultParser.parse(issuesJson: String): List<RawFinding>`；`SonarQubeSeverityMapper.map(engineSeverity: String): String`；`SonarQubeAdapter` @Component（`engine = "SONARQUBE"`，自动注册入 EngineAdapterRegistry）。Task 3 不使用真实适配器（STUBSONAR 桩），本任务单测即真实引擎正确性锚定。

- [ ] **Step 1: build.gradle 增 spring-web**

在 `module-engine-adapter/build.gradle.kts` dependencies 块追加：

```kotlin
    implementation(project(":module-result"))
    implementation("org.springframework:spring-web")   // M15 (R-M15-D7)：RestClient；BOM 3.3.5 管版本
```

- [ ] **Step 2: 写 fixture**

创建 `module-engine-adapter/src/test/resources/sonarqube/issues.json`：

```json
{
  "total": 2,
  "issues": [
    {
      "key": "AXM15_issue_1",
      "rule": "java:S1134",
      "severity": "MAJOR",
      "component": "m15app:src/main/java/com/example/App.java",
      "line": 17,
      "message": "Remove this use of 'TODO'",
      "status": "OPEN",
      "type": "CODE_SMELL"
    },
    {
      "key": "AXM15_issue_2",
      "rule": "java:S2077",
      "severity": "CRITICAL",
      "component": "m15app:src/main/java/com/example/DbHelper.java",
      "line": 42,
      "message": "Make sure that using this pseudorandom number generator is safe here",
      "status": "OPEN",
      "type": "VULNERABILITY"
    }
  ]
}
```

- [ ] **Step 3: 写失败测试（parser）**

创建 `SonarQubeResultParserTest.kt`：

```kotlin
package com.example.compliance.engineadapter.sonarqube

import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SonarQubeResultParserTest {
    private val parser = SonarQubeResultParser()
    private val issues = javaClass.getResource("/sonarqube/issues.json").readText(StandardCharsets.UTF_8)

    @Test
    fun `parses issue json into code-class findings`() {
        val out = parser.parse(issues)
        assertEquals(2, out.size)
        val first = out[0]
        assertEquals("java:S1134", first.engineRuleId)                 // engineRuleId = issue.rule
        assertEquals("src/main/java/com/example/App.java", first.filePath) // component 去 "m15app:" 前缀
        assertEquals(17, first.line)
        assertEquals("MAJOR", first.severity)                          // 原生透传（映射在 normalizeResult）
        assertEquals("Remove this use of 'TODO'", first.message)
        assertEquals("CODE_SMELL", first.category)                     // category = issue.type
        assertNull(first.packageName)                                  // 代码类恒无依赖字段（P3-D8）
        val second = out[1]
        assertEquals("java:S2077", second.engineRuleId)
        assertEquals(42, second.line)
        assertEquals("CRITICAL", second.severity)
        assertEquals("VULNERABILITY", second.category)
    }

    @Test
    fun `invalid or empty input yields empty list`() {
        assertTrue(parser.parse("not json").isEmpty())
        assertTrue(parser.parse("{}").isEmpty())
    }
}
```

- [ ] **Step 4: 运行测试验证失败**

Run: `./gradlew :module-engine-adapter:test --tests "com.example.compliance.engineadapter.sonarqube.SonarQubeResultParserTest"`
Expected: 编译失败 —— `SonarQubeResultParser` 不存在（RED）。

- [ ] **Step 5: 实现 parser**

创建 `SonarQubeResultParser.kt`：

```kotlin
package com.example.compliance.engineadapter.sonarqube

import com.example.compliance.result.engine.RawFinding
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/** SonarQube issue JSON 解析（spec §3.2，R-M15-D10）：issues[] → 代码类 RawFinding（无依赖字段，恒 null）。
 *  engineRuleId = rule；filePath = component 去 "projectKey:" 前缀（substringAfter 首个冒号）；
 *  line = line；severity 原生透传（BLOCKER/CRITICAL/MAJOR/MINOR/INFO）；message = message；
 *  category = type（BUG/VULNERABILITY/CODE_SMELL，信息性字符串，无枚举约束）。 */
@Component
class SonarQubeResultParser {
    private val objectMapper = ObjectMapper()

    fun parse(issuesJson: String): List<RawFinding> {
        val root = runCatching { objectMapper.readTree(issuesJson) }.getOrNull() ?: return emptyList()
        val issues = root.path("issues")
        if (!issues.isArray) return emptyList()
        val out = mutableListOf<RawFinding>()
        for (issue in issues) {
            val rule = issue.path("rule").asText("")
            if (rule.isEmpty()) continue
            out += RawFinding(
                engineRuleId = rule,
                ruleName = null,
                filePath = issue.path("component").asText("").substringAfter(":"),
                line = issue.path("line").takeIf { it.isNumber }?.asInt(),
                severity = issue.path("severity").asText("INFO"),
                message = issue.path("message").takeIf { !it.isMissingNode }?.asText(),
                codeSnippet = null,
                category = issue.path("type").takeIf { !it.isMissingNode }?.asText(),
            )
        }
        return out
    }
}
```

- [ ] **Step 6: 运行 parser 测试验证通过**

Run: `./gradlew :module-engine-adapter:test --tests "com.example.compliance.engineadapter.sonarqube.SonarQubeResultParserTest"`
Expected: PASS（2 tests）。

- [ ] **Step 7: 写失败测试（mapper）**

创建 `SonarQubeSeverityMapperTest.kt`：

```kotlin
package com.example.compliance.engineadapter.sonarqube

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SonarQubeSeverityMapperTest {
    private val mapper = SonarQubeSeverityMapper()

    @Test
    fun `maps native sonarqube severities`() {
        assertEquals("CRITICAL", mapper.map("BLOCKER"))   // R-M15-D6：首个可达 CRITICAL 的引擎
        assertEquals("HIGH", mapper.map("CRITICAL"))
        assertEquals("MEDIUM", mapper.map("MAJOR"))
        assertEquals("LOW", mapper.map("MINOR"))
        assertEquals("LOW", mapper.map("INFO"))
    }

    @Test
    fun `maps unknown severities to LOW`() {
        assertEquals("LOW", mapper.map("UNKNOWN"))
        assertEquals("LOW", mapper.map(""))
    }
}
```

- [ ] **Step 8: 实现 mapper**

创建 `SonarQubeSeverityMapper.kt`：

```kotlin
package com.example.compliance.engineadapter.sonarqube

import org.springframework.stereotype.Component

/** SonarQube 原生 severity（BLOCKER/CRITICAL/MAJOR/MINOR/INFO）→ 统一等级（R-M15-D6；镜像既有 mapper）。 */
@Component
class SonarQubeSeverityMapper {
    fun map(engineSeverity: String): String = when (engineSeverity.uppercase()) {
        "BLOCKER" -> "CRITICAL"
        "CRITICAL" -> "HIGH"
        "MAJOR" -> "MEDIUM"
        "MINOR" -> "LOW"
        "INFO" -> "LOW"
        else -> "LOW"
    }
}
```

- [ ] **Step 9: 运行 mapper 测试验证通过**

Run: `./gradlew :module-engine-adapter:test --tests "com.example.compliance.engineadapter.sonarqube.SonarQubeSeverityMapperTest"`
Expected: PASS（2 tests）。

- [ ] **Step 10: 写失败测试（api client）**

创建 `SonarQubeApiClientTest.kt`：

```kotlin
package com.example.compliance.engineadapter.sonarqube

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestTemplate
import kotlin.test.assertEquals

/** MockRestServiceServer（spring-test）绑定 RestTemplate → RestClient（R-M15-D7）：Bearer token + URI 逐字断言。 */
class SonarQubeApiClientTest {
    private val restTemplate = RestTemplate()
    private val mockServer = MockRestServiceServer.bindTo(restTemplate).build()
    private val client = SonarQubeApiClient(RestClient.builder(restTemplate).build())

    @Test
    fun `ce task status parses status from response`() {
        mockServer.expect(requestTo("http://sq:9000/api/ce/task?id=AX1"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
            .andRespond(withSuccess("""{"task":{"status":"SUCCESS"}}""", MediaType.APPLICATION_JSON))
        assertEquals("SUCCESS", client.ceTaskStatus("http://sq:9000", "AX1", "tok"))
        mockServer.verify()
    }

    @Test
    fun `ce task in progress`() {
        mockServer.expect(requestTo("http://sq:9000/api/ce/task?id=AX2"))
            .andRespond(withSuccess("""{"task":{"status":"IN_PROGRESS"}}""", MediaType.APPLICATION_JSON))
        assertEquals("IN_PROGRESS", client.ceTaskStatus("http://sq:9000", "AX2", "tok"))
        mockServer.verify()
    }

    @Test
    fun `issues returns raw json body`() {
        val issuesJson = """{"total":1,"issues":[]}"""
        mockServer.expect(requestTo("http://sq:9000/api/issues/search?componentKeys=m15app&resolved=false&ps=500"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
            .andRespond(withSuccess(issuesJson, MediaType.APPLICATION_JSON))
        assertEquals(issuesJson, client.issues("http://sq:9000", "m15app", "tok"))
        mockServer.verify()
    }
}
```

- [ ] **Step 11: 实现 api client**

创建 `SonarQubeApiClient.kt`：

```kotlin
package com.example.compliance.engineadapter.sonarqube

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/** SonarQube REST API 客户端（R-M15-D5/D7）：RestClient 构造器注入（测试可绑定 MockRestServiceServer）。
 *  ceTaskStatus → task.status 字符串；issues → 原始 JSON（componentKeys + resolved=false 未决 issue + ps=500 单页）。 */
@Component
class SonarQubeApiClient(
    private val restClient: RestClient = RestClient.create(),
) {
    private val objectMapper = ObjectMapper()

    fun ceTaskStatus(serverUrl: String, taskId: String, token: String): String {
        val body = restClient.get()
            .uri("$serverUrl/api/ce/task?id=$taskId")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .retrieve()
            .body(String::class.java) ?: "{}"
        return runCatching { objectMapper.readTree(body).path("task").path("status").asText("") }.getOrElse { "" }
    }

    fun issues(serverUrl: String, projectKey: String, token: String): String =
        restClient.get()
            .uri("$serverUrl/api/issues/search?componentKeys=$projectKey&resolved=false&ps=500")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .retrieve()
            .body(String::class.java) ?: "{}"
}
```

- [ ] **Step 12: 运行 api client 测试验证通过**

Run: `./gradlew :module-engine-adapter:test --tests "com.example.compliance.engineadapter.sonarqube.SonarQubeApiClientTest"`
Expected: PASS（3 tests）。

- [ ] **Step 13: 实现 SonarQubeCli（interface + ProcessSonarQubeCli）**

创建 `SonarQubeCli.kt`：

```kotlin
package com.example.compliance.engineadapter.sonarqube

import com.example.compliance.engineadapter.cli.CliExecutor
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

interface SonarQubeCli {
    fun run(workDir: String, projectKey: String, token: String, serverUrl: String): String
}

/** sonar-scanner CLI（spec §3.2）：四个 -D 属性 + SONAR_TOKEN 环境变量（R-M15-D3，token 不进 argv）。
 *  CliExecutor 无 working-dir → -Dsonar.projectBaseDir=<workDir> + -Dsonar.sources=. 指向检出目录；
 *  mergeErrorStream=true → 合并输出含 CE task URL（R-M15-D9 从中提取）。 */
@Component
class ProcessSonarQubeCli(
    @Value("\${app.sonarqube.timeout-seconds:900}")
    private val timeoutSeconds: Long,
) : SonarQubeCli {
    private val executor = CliExecutor(timeoutSeconds)

    override fun run(workDir: String, projectKey: String, token: String, serverUrl: String): String =
        executor.run(
            command = listOf(
                "sonar-scanner",
                "-Dsonar.projectKey=$projectKey",
                "-Dsonar.host.url=$serverUrl",
                "-Dsonar.projectBaseDir=$workDir",
                "-Dsonar.sources=.",
            ),
            label = "sonar-scanner",
            config = CliExecutor.Config(
                mergeErrorStream = true,
                successExitCodes = setOf(0),
                env = mapOf("SONAR_TOKEN" to token),   // R-M15-D3：token 仅经环境变量，命令列表绝不含 token
            ),
        )
}
```

- [ ] **Step 14: 写失败测试（adapter）**

创建 `SonarQubeAdapterTest.kt`：

```kotlin
package com.example.compliance.engineadapter.sonarqube

import com.example.compliance.result.engine.ScanContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 适配器测试镜像 DetektAdapterTest + 服务端型失败路径（R-M15-D1 三段：scanner → CE 轮询 → issues 拉取）。 */
class SonarQubeAdapterTest {
    private val cli = mockk<SonarQubeCli>()
    private val apiClient = mockk<SonarQubeApiClient>()
    private val adapter = SonarQubeAdapter(cli, apiClient, SonarQubeResultParser(), SonarQubeSeverityMapper(), "http://sq:9000", 900)
    private val fastAdapter = SonarQubeAdapter(cli, apiClient, SonarQubeResultParser(), SonarQubeSeverityMapper(), "http://sq:9000", 1, pollIntervalMs = 10)  // R-M15-1：短轮询间隔使超时测试快

    private val issues = javaClass.getResource("/sonarqube/issues.json").readText(StandardCharsets.UTF_8)
    private val taskUrl = "More about the report processing at http://sq:9000/api/ce/task?id=AX123"
    private val ctx = ScanContext(1L, 1L, "https://git.example.com/m15app.git", "main", workDir = "/tmp/scan", credentialToken = "tok")
    private val failCtx = ScanContext(101L, 1L, "https://git.example.com/m15app.git", "main", workDir = "/tmp/scan", credentialToken = "tok")
    private val noCredCtx = ScanContext(201L, 1L, "https://git.example.com/m15app.git", "main", workDir = "/tmp/scan", credentialToken = null)

    @Test
    fun `execute collect normalize maps severity and keeps code fields`() {
        // R-M15-4：断言 cli 参数逐字（projectKey 派生自 repoUrl + token 传递 + serverUrl）
        every { cli.run(any(), "m15app", "tok", "http://sq:9000") } returns taskUrl
        every { apiClient.ceTaskStatus(any(), "AX123", "tok") } returns "SUCCESS"
        every { apiClient.issues("http://sq:9000", "m15app", "tok") } returns issues
        val execution = adapter.executeScan(ctx)
        assertTrue(execution.success)
        assertTrue(execution.stdoutRef != null)
        val raw = adapter.collectResult(ctx)
        assertEquals(2, raw.size)
        assertEquals("MAJOR", raw[0].severity)               // 原生透传
        val normalized = adapter.normalizeResult(ctx, raw)
        assertEquals("MEDIUM", normalized[0].severity)       // MAJOR → MEDIUM
        assertEquals("HIGH", normalized[1].severity)         // CRITICAL → HIGH
        assertEquals("src/main/java/com/example/App.java", normalized[0].filePath)
    }

    @Test
    fun `missing credential yields unsuccessful execution without stdout file`() {
        val execution = adapter.executeScan(noCredCtx)
        assertTrue(!execution.success)
        assertTrue(execution.stdoutRef == null)              // F1: 失败不落盘 stdout
    }

    @Test
    fun `cli failure maps to unsuccessful execution without stdout file`() {
        every { cli.run(any(), any(), any(), any()) } throws IllegalStateException("sonar-scanner exited with code 1")
        val execution = adapter.executeScan(failCtx)
        assertTrue(!execution.success)
        assertTrue(execution.stdoutRef == null)
        assertTrue(adapter.collectResult(failCtx).isEmpty())
    }

    @Test
    fun `missing ce task id yields unsuccessful execution without stdout file`() {
        every { cli.run(any(), any(), any(), any()) } returns "analysis finished without task id"
        val execution = adapter.executeScan(failCtx)
        assertTrue(!execution.success)
        assertTrue(execution.stdoutRef == null)
    }

    @Test
    fun `ce task failure yields unsuccessful execution without stdout file`() {
        every { cli.run(any(), any(), any(), any()) } returns taskUrl
        every { apiClient.ceTaskStatus(any(), any(), any()) } returns "FAILED"
        val execution = adapter.executeScan(failCtx)
        assertTrue(!execution.success)
        assertTrue(execution.stdoutRef == null)
    }

    @Test
    fun `ce task timeout yields unsuccessful execution without stdout file`() {
        every { cli.run(any(), any(), any(), any()) } returns taskUrl
        every { apiClient.ceTaskStatus(any(), any(), any()) } returns "IN_PROGRESS"
        val execution = fastAdapter.executeScan(failCtx)   // 1s 总超时 + 10ms 轮询（R-M15-1）
        assertTrue(!execution.success)
        assertTrue(execution.stdoutRef == null)
    }

    @AfterEach
    fun tearDown() {
        adapter.cleanup(ctx)
        adapter.cleanup(failCtx)
        adapter.cleanup(noCredCtx)
    }

    @Test
    fun `cleanup clears stdout file`() {
        every { cli.run(any(), "m15app", "tok", "http://sq:9000") } returns taskUrl
        every { apiClient.ceTaskStatus(any(), any(), any()) } returns "SUCCESS"
        every { apiClient.issues(any(), any(), any()) } returns issues
        adapter.executeScan(ctx)
        adapter.collectResult(ctx)
        adapter.cleanup(ctx)
        assertTrue(adapter.collectResult(ctx).isEmpty())
    }

    @Test
    fun `engine name is SONARQUBE`() {
        assertEquals("SONARQUBE", adapter.engine)
    }
}
```

- [ ] **Step 15: 运行 adapter 测试验证失败**

Run: `./gradlew :module-engine-adapter:test --tests "com.example.compliance.engineadapter.sonarqube.SonarQubeAdapterTest"`
Expected: 编译失败 —— `SonarQubeAdapter` 不存在（RED）。

- [ ] **Step 16: 实现 adapter**

创建 `SonarQubeAdapter.kt`：

```kotlin
package com.example.compliance.engineadapter.sonarqube

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanExecutionResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File

/** SonarQube 适配器（spec §3.4，R-M15-D1/D5/D9）：五方法镜像 DetektAdapter（代码类 stdout-file 语义）。
 *  executeScan 三段：① cli 跑 sonar-scanner（SONAR_TOKEN env）→ ② 提取 CE taskId + 轮询至 SUCCESS/FAILED/CANCELED/超时
 *  → ③ 拉取 issue JSON 落盘 stdout 文件。F1：任一失败 success=false 不落盘（绝不产出假干净扫描）。 */
@Component
class SonarQubeAdapter(
    private val cli: SonarQubeCli,
    private val apiClient: SonarQubeApiClient,
    private val parser: SonarQubeResultParser,
    private val severityMapper: SonarQubeSeverityMapper,
    @Value("\${app.sonarqube.server-url:http://localhost:9000}") private val serverUrl: String,
    @Value("\${app.sonarqube.timeout-seconds:900}") private val timeoutSeconds: Long,
    private val pollIntervalMs: Long = 5_000,   // R-M15-1：轮询间隔可注（测试缩短）
) : ScanEngineAdapter {

    override val engine: String = "SONARQUBE"

    override fun prepareScan(context: ScanContext) {}

    override fun executeScan(context: ScanContext): ScanExecutionResult {
        val start = System.currentTimeMillis()
        return runCatching {
            val token = context.credentialToken
                ?: throw IllegalStateException("sonarqube credential missing for project ${context.projectId}")
            val workDir = context.workDir
                ?: throw IllegalStateException("sonarqube requires a checkout workDir")
            val projectKey = projectKeyOf(context)
            val output = cli.run(workDir, projectKey, token, serverUrl)
            val taskId = extractTaskId(output)
                ?: throw IllegalStateException("sonarqube ce task id not found in scanner output")
            awaitCeTask(taskId, token, start)
            val issues = apiClient.issues(serverUrl, projectKey, token)
            val file = stdoutFile(context)
            file.writeText(issues)
            ScanExecutionResult(success = true, stdoutRef = file.absolutePath)
        }.getOrElse { e ->
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

    private fun awaitCeTask(taskId: String, token: String, start: Long) {
        val deadline = start + timeoutSeconds * 1000
        while (true) {
            val status = apiClient.ceTaskStatus(serverUrl, taskId, token)
            if (status == "SUCCESS") return
            if (status == "FAILED" || status == "CANCELED") {
                throw IllegalStateException("sonarqube ce task $status (taskId=$taskId)")
            }
            if (System.currentTimeMillis() >= deadline) {
                throw IllegalStateException("sonarqube ce task timed out after ${timeoutSeconds}s (taskId=$taskId)")
            }
            Thread.sleep(pollIntervalMs)
        }
    }

    private fun projectKeyOf(context: ScanContext): String =
        context.repoUrl.substringAfterLast("/").removeSuffix(".git")
            .ifBlank { throw IllegalStateException("cannot derive sonarqube project key from repoUrl: ${context.repoUrl}") }

    private fun extractTaskId(output: String): String? =
        Regex("ce/task\\?id=([A-Za-z0-9_-]+)").find(output)?.groupValues?.get(1)

    private fun stdoutFile(context: ScanContext): File =
        File(System.getProperty("java.io.tmpdir"), "sonarqube-stdout-${context.scanTaskId}.json")
}
```

- [ ] **Step 17: 运行 adapter 测试验证通过**

Run: `./gradlew :module-engine-adapter:test --tests "com.example.compliance.engineadapter.sonarqube.*"`
Expected: PASS —— 15 new tests（parser 2 + mapper 2 + api client 3 + adapter 8），其中 timeout 测试 ~1s（R-M15-1）。

- [ ] **Step 18: 全量模块测试 + build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL —— module-engine-adapter 既有 46 + 新增 15 = 61 全绿；app-server 集成套件（含 M14 的 checkout-engines membership 断言）全绿；新 SonarQubeAdapter @Component 自动注册入 EngineAdapterRegistry，app-server 上下文实例化（@Value 有默认值 → yml 未改也能启动）。

- [ ] **Step 19: Commit**

```bash
git add module-engine-adapter/build.gradle.kts \
        module-engine-adapter/src/main/kotlin/com/example/compliance/engineadapter/sonarqube/ \
        module-engine-adapter/src/test/kotlin/com/example/compliance/engineadapter/sonarqube/ \
        module-engine-adapter/src/test/resources/sonarqube/issues.json
git commit -m "feat(engine-adapter): SonarQube engine (cli/api-client/parser/mapper/adapter) (m15)"
```

---

### Task 3: M15 集成测试 + 配置（app-server）

**Files:**
- Test: Create `app-server/src/test/kotlin/com/example/compliance/scan/M15EngineIntegrationTest.kt`
- Modify: `app-server/src/main/resources/application.yml`（app.sonarqube.* + checkout-engines += SONARQUBE）

**Interfaces:**
- Consumes: Task 1 的编排器凭证注入路径（`credentialToken = decrypt(credentialRef)`——本任务用 STUBSONAR 桩在 prepareScan 记录并断言，端到端锚定 R-M15-D2）；`ScanEngineAdapter` 默认方法（STUBSONAR 只 override engine/prepareScan/collectResult，其余默认）；`application.yml` `app.scan.checkout-engines`。
- Produces: `M15EngineIntegrationTest`（STUBSONAR e2e + credentialToken 解密断言 + checkout-engines 含 SONARQUBE 断言）；application.yml 增 `app.sonarqube.server-url`、`app.sonarqube.timeout-seconds`、`checkout-engines` 追加 `SONARQUBE`。真实 `SonarQubeAdapter` 不参与 e2e（STUBSONAR 桩镜像 M11/M14），其正确性由 Task 2 单测锚定。

- [ ] **Step 1: 更新 application.yml**

在 `app-server/src/main/resources/application.yml` 的 `detekt:` 块之后追加：

```yaml
  sonarqube:
    server-url: ${SONARQUBE_SERVER_URL:http://localhost:9000}   # spec §3.5：真实环境必配；默认 localhost 便于启动
    timeout-seconds: 900                                          # CE 轮询总超时（R-M15-D1）
```

`scan:` 块的 `checkout-engines` 行替换为（追加 SONARQUBE）：

```yaml
    checkout-engines: SEMGREP,GITLEAKS,TRIVY,DEPENDENCYCHECK,DETEKT,SONARQUBE   # M15：SONARQUBE 扫检出目录 → 触发 GitCheckout
```

- [ ] **Step 2: 写集成测试**

创建 `app-server/src/test/kotlin/com/example/compliance/scan/M15EngineIntegrationTest.kt`：

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

/** M15 引擎契约集成测试（镜像 M11/M14）：STUBSONAR 桩接入 + 编排器凭证解密注入端到端 + checkout-engines 配置断言。
 *  数据前缀 M15-*；STUBSONAR 不在 checkout-engines → commitId null。
 *  桩引擎返回归一化 severity（RawFinding 契约）；STUBSONAR 记录 context.credentialToken 断言 R-M15-D2 解密回环
 *  （BindRepositoryCommand("tok") → credentialRef=encrypt("tok") → 编排器 decrypt → "tok"）。 */
class M15EngineIntegrationTest : AbstractIntegrationTest() {

    object StubState {
        @Volatile var lastCredentialToken: String? = null
    }

    @TestConfiguration
    class StubAdaptersConfig {
        @Bean
        fun stubSonarQubeAdapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUBSONAR"
            override fun prepareScan(context: ScanContext) { StubState.lastCredentialToken = context.credentialToken }
            override fun collectResult(context: ScanContext): List<RawFinding> = listOf(RawFinding(
                "stub-sq-rule", "M15 SonarQube", "src/main/java/com/example/App.java", 17, "HIGH",
                "use of TODO",
            ))
        }
    }

    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService
    @Autowired lateinit var lifecyclePort: FindingLifecyclePort
    @Value("\${app.scan.checkout-engines}") lateinit var checkoutEngines: Set<String>

    @Test
    fun `sonarqube-named stub persists code-class finding and credential token round-trips end to end`() {
        val project = projectService.create(CreateProjectCommand("M15SQ", "M15 sonarqube", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("m15sq-repo", "https://git.example.com/m15sq.git", "GITLAB", "main", "tok"))
        val rule = ruleService.create(CreateRuleCommand("M15-SQ", "M15 SonarQube 规则", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUBSONAR", "stub-sq-rule", null))
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'HIGH'"))
        ruleService.publish(rule.id!!)

        val task = scanTaskService.startScan(project.id!!, "STUBSONAR", "main")
        var done = false
        repeat(50) {
            val s = scanTaskService.get(task.id!!).status
            if (s != ScanTaskStatus.PENDING && s != ScanTaskStatus.PREPARING && s != ScanTaskStatus.RUNNING) { done = true; return@repeat }
            Thread.sleep(200)
        }
        assertTrue(done, "scan should finish within timeout")
        assertEquals(ScanTaskStatus.SUCCESS, scanTaskService.get(task.id!!).status)
        assertNull(scanTaskService.get(task.id!!).commitId, "STUBSONAR not in checkout-engines -> commitId null")
        assertEquals("tok", StubState.lastCredentialToken, "orchestrator should decrypt credentialRef into credentialToken")

        val views = lifecyclePort.findingsForScanTask(task.id!!)
        assertEquals(1, views.size)
        val v = views[0]
        assertEquals("M15-SQ", v.ruleCode)
        assertEquals("STUBSONAR", v.engine)
        assertEquals("src/main/java/com/example/App.java", v.filePath)
        assertEquals(17, v.lineNumber)
        assertNull(v.packageName, "code-class finding has no dependency fields")
    }

    @Test
    fun `checkout-engines config contains sonarqube plus existing engines`() {
        assertTrue("SEMGREP" in checkoutEngines)
        assertTrue("GITLEAKS" in checkoutEngines)
        assertTrue("TRIVY" in checkoutEngines)
        assertTrue("DEPENDENCYCHECK" in checkoutEngines)
        assertTrue("DETEKT" in checkoutEngines)
        assertTrue("SONARQUBE" in checkoutEngines)
    }
}
```

- [ ] **Step 3: 运行集成测试验证通过**

Run: `./gradlew :app-server:test --tests "*M15EngineIntegrationTest*"`
Expected: PASS —— 2 tests（live Testcontainers PG，Docker 须 up；共享容器 max_connections=300 不改）。`assertEquals("tok", StubState.lastCredentialToken)` 验证 R-M15-D2 解密回环（encrypt→decrypt round-trip 走真实 CredentialCrypto）。

- [ ] **Step 4: 全量 build 验证**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL —— 全模块全绿（module-engine-adapter 61、module-result、app-server 集成套件含 M15 新 2 tests；M14 的 checkout-engines 断言为 membership，加 SONARQUBE 不破坏）。

- [ ] **Step 5: Commit**

```bash
git add app-server/src/test/kotlin/com/example/compliance/scan/M15EngineIntegrationTest.kt \
        app-server/src/main/resources/application.yml
git commit -m "test(app-server): M15 stub-engine E2E for sonarqube + checkout-engines config (m15)"
```
