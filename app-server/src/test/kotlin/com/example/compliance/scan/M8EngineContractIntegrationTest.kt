package com.example.compliance.scan

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.checklist.application.ChecklistService
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
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8 引擎契约集成测试：STUBM8 以五方法形态接入，验证编排器五阶段管线 + PREPARING + 门控 checkout（commitId null）+ cleanup finally。
 *  数据前缀 M8-*。 */
class M8EngineContractIntegrationTest : AbstractIntegrationTest() {

    object StubM8State {
        @Volatile var prepared = false
        @Volatile var executed = false
        @Volatile var collected = false
        @Volatile var cleanupCalled = false
    }

    @TestConfiguration
    class StubM8AdapterConfig {
        @Bean
        fun stubM8Adapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUBM8"
            override fun prepareScan(context: ScanContext) { StubM8State.prepared = true }
            override fun executeScan(context: ScanContext): ScanExecutionResult {
                StubM8State.executed = true
                return ScanExecutionResult(success = true, durationMs = 5)
            }
            override fun collectResult(context: ScanContext): List<RawFinding> {
                StubM8State.collected = true
                return listOf(RawFinding("stub-m8-rule", "M8", "src/main/java/M8.java", 10, "HIGH", "m", "x=id;"))
            }
            override fun normalizeResult(context: ScanContext, raw: List<RawFinding>): List<RawFinding> = raw
            override fun cleanup(context: ScanContext) { StubM8State.cleanupCalled = true }
        }
    }

    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var checklistService: ChecklistService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService
    @Autowired lateinit var lifecyclePort: FindingLifecyclePort

    @Test
    fun `orchestrator drives five stages with cleanup and skips checkout for stub engine`() {
        // 1. 项目 + 仓库
        val project = projectService.create(CreateProjectCommand("M8P", "M8 项目", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("m8-repo", "https://git.example.com/m8.git", "GITLAB", "main", "tok"))
        // 2. 规则（STUBM8 绑定，无需清单——本次只断言引擎契约）
        val rule = ruleService.create(CreateRuleCommand("M8-SQLI", "M8 注入", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUBM8", "stub-m8-rule", null))
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'HIGH'"))
        ruleService.publish(rule.id!!)

        // 3. 触发扫描并等待完成（谓词含 PREPARING）
        StubM8State.prepared = false; StubM8State.executed = false
        StubM8State.collected = false; StubM8State.cleanupCalled = false
        val task = scanTaskService.startScan(project.id!!, "STUBM8", "main")
        var done = false
        repeat(50) {
            val s = scanTaskService.get(task.id!!).status
            if (s != ScanTaskStatus.PENDING && s != ScanTaskStatus.PREPARING && s != ScanTaskStatus.RUNNING) { done = true; return@repeat }
            Thread.sleep(200)
        }
        assertTrue(done, "scan should finish within timeout")
        assertEquals(ScanTaskStatus.SUCCESS, scanTaskService.get(task.id!!).status)

        // 4. 契约断言：五阶段全部驱动 + cleanup 在 finally + STUBM8 未检出（commitId null）
        assertTrue(StubM8State.prepared, "prepareScan called")
        assertTrue(StubM8State.executed, "executeScan called")
        assertTrue(StubM8State.collected, "collectResult called")
        assertTrue(StubM8State.cleanupCalled, "cleanup called in finally")
        assertNull(scanTaskService.get(task.id!!).commitId, "STUBM8 not in checkout-engines -> commitId null")

        // 5. 结果贯通：finding 归属本次扫描（五阶段产物进 occurrence 查询）
        val views = lifecyclePort.findingsForScanTask(task.id!!)
        assertEquals(1, views.size)
        assertEquals("M8-SQLI", views[0].ruleCode)
        assertEquals("STUBM8", views[0].engine)  // FindingView.engine 由 m6b 编辑批次补尾字段（发现引擎）
    }
}
