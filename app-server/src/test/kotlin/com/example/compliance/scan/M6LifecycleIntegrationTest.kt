package com.example.compliance.scan

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.checklist.application.ChecklistService
import com.example.compliance.project.application.BindRepositoryCommand
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
import com.example.compliance.result.application.FindingLifecyclePort
import com.example.compliance.result.domain.FindingStatus
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** M6-* 数据前缀；独立 @TestConfiguration STUB 适配器（stub-m6-rule），与冻结 ScanPipeline 的 stub-rule-sqli 不冲突。 */
class M6LifecycleIntegrationTest : AbstractIntegrationTest() {

    @TestConfiguration
    class StubM6AdapterConfig {
        @Bean
        fun stubM6Adapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUBM6"
            // M8 新契约：编排器直接驱动五阶段，STUB 按五方法形态接入（Ruling：测试 STUB 适配器按新契约更新）
            override fun executeScan(context: ScanContext): ScanExecutionResult =
                ScanExecutionResult(success = true, durationMs = 5)
            override fun collectResult(context: ScanContext): List<RawFinding> =
                listOf(RawFinding("stub-m6-rule", "M6", "src/main/java/M6.java", 10, "HIGH", "m", "x=id;"))
        }
    }

    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var checklistService: ChecklistService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService
    @Autowired lateinit var lifecyclePort: FindingLifecyclePort

    @Test
    fun `re-scan attributes findings via history and stamps checklist version`() {
        // 1. 项目 + 仓库
        val project = projectService.create(CreateProjectCommand("M6P", "M6 项目", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("m6-repo", "https://git.example.com/m6.git", "GITLAB", "main", "tok"))
        // 2. 清单 → 发布 → 绑定
        val standard = checklistService.createStandard("M6-SEC", "M6 规范", null)
        val checklist = checklistService.createChecklist(standard.id!!, "M6-BASIC", "M6 基线")
        checklistService.addItem(checklist.id!!, com.example.compliance.checklist.application.AddItemCommand(itemCode = "M6-001", name = "M6 项", riskLevel = "HIGH"))
        val version = checklistService.publish(checklist.id!!)
        checklistService.bindProject(project.id!!, version.id!!)
        // 3. 规则（STUBM6 引擎绑定 + 映射 + FAIL 策略）
        val rule = ruleService.create(CreateRuleCommand("M6-SQLI", "M6 注入", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUBM6", "stub-m6-rule", null))
        ruleService.addComplianceMapping(rule.id!!, "M6-001")
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'HIGH'"))
        ruleService.publish(rule.id!!)
        // 4. 两次扫描
        val task1 = scanTaskService.startScan(project.id!!, "STUBM6", "main")
        waitDone(task1.id!!)
        val task2 = scanTaskService.startScan(project.id!!, "STUBM6", "main")
        waitDone(task2.id!!)
        // 5. 断言：版本盖章 + occurrence 归属 + findingCount
        val t2 = scanTaskService.get(task2.id!!)
        assertEquals(version.id, t2.checklistVersionId)
        assertNotNull(t2.ruleIds)
        assertEquals(1, t2.findingCount)                       // occurrence 口径：本次扫描全部命中 = 1（同一指纹复现）
        assertEquals(1, scanTaskService.findings(task2.id!!).size)   // occurrence 视图含复现的 finding
        val views = lifecyclePort.findingsForScanTask(task2.id!!)
        assertEquals(1, views.size)
        assertEquals(FindingStatus.NEW, views[0].status)       // 活动集 → 状态不变
        assertEquals(2, views[0].occurrenceCount)              // 两次扫描命中
        // 6. 评估带版本
        val compliance = scanTaskService.complianceResults(task2.id!!)
        assertEquals(version.id, compliance.evaluation?.checklistVersionId)
        assertEquals(1, compliance.evaluation?.failed)
    }

    private fun waitDone(taskId: Long) {
        var done = false
        repeat(50) {
            val s = scanTaskService.get(taskId).status
            if (s != ScanTaskStatus.RUNNING && s != ScanTaskStatus.PENDING && s != ScanTaskStatus.PREPARING) { done = true; return@repeat }
            Thread.sleep(200)
        }
        assertTrue(done, "scan $taskId should finish within timeout")
        assertEquals(ScanTaskStatus.SUCCESS, scanTaskService.get(taskId).status)
    }
}
