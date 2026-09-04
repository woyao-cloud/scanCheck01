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
        assertEquals(17, v.lineNumber)
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
