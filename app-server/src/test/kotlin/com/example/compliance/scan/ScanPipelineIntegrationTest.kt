package com.example.compliance.scan

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.checklist.application.ChecklistService
import com.example.compliance.project.application.BindRepositoryCommand
import com.example.compliance.project.application.CreateProjectCommand
import com.example.compliance.project.application.ProjectService
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

class ScanPipelineIntegrationTest : AbstractIntegrationTest() {

    @TestConfiguration
    class StubAdapterConfig {
        @Bean
        fun stubAdapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUB"
            // M8 新契约：编排器直接驱动五阶段，STUB 按五方法形态接入（Ruling：测试 STUB 适配器按新契约更新）
            override fun executeScan(context: ScanContext): ScanExecutionResult =
                ScanExecutionResult(success = true, durationMs = 5)
            override fun collectResult(context: ScanContext): List<RawFinding> = listOf(
                RawFinding("stub-rule-sqli", "Stub SQLi", "src/main/java/Demo.java", 10, "HIGH", "inject", "x = id;"),
            )
        }
    }

    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var checklistService: ChecklistService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService

    @Test
    fun `full pipeline produces finding and evaluation`() {
        // 1. 项目 + 仓库
        val project = projectService.create(CreateProjectCommand("PIPE", "流水线项目", null, null))
        projectService.bindRepository(
            project.id!!,
            BindRepositoryCommand("repo-a", "https://git.example.com/a.git", "GITLAB", "main", "tok"),
        )
        // 2. 标准 → 清单 → 条目 → 发布 → 绑定（PIPE-* 系列：与冻结 Task 3.1 的 SEC-* / Task 3.2 的 SEC2-* 在同一共享容器中必须 disjoint，Ruling #43）
        val standard = checklistService.createStandard("PIPE-SEC", "安全规范", null)
        val checklist = checklistService.createChecklist(standard.id!!, "PIPE-BASIC", "安全基线")
        // Ruling #47: 用命名参数，riskLevel=HIGH（第 3 个位置形参是 category 不是 riskLevel）
        checklistService.addItem(checklist.id!!, com.example.compliance.checklist.application.AddItemCommand(itemCode = "PIPE-001", name = "防注入", riskLevel = "HIGH"))
        val version = checklistService.publish(checklist.id!!)
        checklistService.bindProject(project.id!!, version.id!!)
        // 3. 规则：引擎绑定 + 清单映射 + FAIL 策略（severity==HIGH）
        val rule = ruleService.create(CreateRuleCommand("STUB-SQLI", "Stub注入", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUB", "stub-rule-sqli", null))
        ruleService.addComplianceMapping(rule.id!!, "PIPE-001")
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'HIGH'"))
        ruleService.publish(rule.id!!)
        // 4. 触发扫描并轮询等待完成
        val task = scanTaskService.startScan(project.id!!, "STUB", "main")
        var done = false
        repeat(50) {
            if (scanTaskService.get(task.id!!).status != ScanTaskStatus.RUNNING &&
                scanTaskService.get(task.id!!).status != ScanTaskStatus.PENDING &&
                scanTaskService.get(task.id!!).status != ScanTaskStatus.PREPARING
            ) { done = true; return@repeat }
            Thread.sleep(200)
        }
        kotlin.test.assertTrue(done, "scan should finish within timeout")
        val finished = scanTaskService.get(task.id!!)
        kotlin.test.assertEquals(ScanTaskStatus.SUCCESS, finished.status)
        // Deviation (documented): filter by the scan's own ruleCode. The frozen Task 3.1
        // FindingRepositoryIntegrationTest hardcodes scanTaskId = 1L, which collides with this
        // scan task's auto-increment id 1 in the shared Testcontainers container — same class
        // of collision as Ruling #43 (PIPE-* vs SEC-*). Counting only STUB-SQLI findings keeps
        // the assertion's intent (exactly one finding from this scan) under either ordering.
        kotlin.test.assertEquals(1, scanTaskService.findings(task.id!!).count { it.ruleCode == "STUB-SQLI" })
    }
}
