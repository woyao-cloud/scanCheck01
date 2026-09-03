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
            override fun collectResult(context: ScanContext): List<RawFinding> {
                StubState.collected = true
                return listOf(RawFinding("stub-g-key", "M11 Gitleaks", "src/main/resources/application.yml", 12, "HIGH", "Found a generic API key", "apiKey = \"sk-1234\""))
            }
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
            override fun collectResult(context: ScanContext): List<RawFinding> {
                StubState.collected = true
                return listOf(RawFinding(
                    "stub-t-cve-1", "M11 Trivy", "package-lock.json", null, "CRITICAL",
                    "lodash prototype pollution", null, null,
                    "lodash", "4.17.20", "4.17.21", "CVE-2024-1234", 9.8,
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
