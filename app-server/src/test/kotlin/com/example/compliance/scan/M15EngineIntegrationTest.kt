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
