package com.example.compliance.report

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.checklist.application.AddItemCommand
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// Ruling #49: MockMvc hits the live Task 1.3 security chain (everything authenticated) — @WithMockUser required.
@AutoConfigureMockMvc
@WithMockUser
class ReportApiIntegrationTest : AbstractIntegrationTest() {

    @TestConfiguration
    class StubAdapterConfig {
        @Bean
        fun stubAdapter(): ScanEngineAdapter = object : ScanEngineAdapter {
            override val engine = "STUB"
            // M8 新契约：编排器直接驱动五阶段，STUB 按五方法形态接入（Ruling：测试 STUB 适配器按新契约更新）。
            // Deviation (documented in task-5.1-report.md): engine rule id is "rpt-rule-sqli", NOT the
            // brief's "stub-rule-sqli" — the frozen ScanPipelineIntegrationTest binds STUB-SQLI to
            // ("STUB","stub-rule-sqli") in the same shared container, and RuleQueryService
            // .publishedRuleByEngineRuleId returns firstOrNull{PUBLISHED} over all matching bindings,
            // so a second rule on the same engine rule id makes BOTH scans' rule resolution ambiguous
            // (breaks the frozen test's ruleCode count). Distinct engine rule id keeps both unambiguous.
            override fun executeScan(context: ScanContext): ScanExecutionResult =
                ScanExecutionResult(success = true, durationMs = 5)
            override fun collectResult(context: ScanContext): List<RawFinding> = listOf(
                RawFinding("rpt-rule-sqli", "Rpt SQLi", "Demo.java", 10, "HIGH", "inject", "x = id;")
            )
        }
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var projectService: ProjectService
    @Autowired lateinit var checklistService: ChecklistService
    @Autowired lateinit var ruleService: RuleService
    @Autowired lateinit var scanTaskService: ScanTaskService

    @Test
    fun `reports return summary and compliance data after pipeline scan`() {
        val project = projectService.create(CreateProjectCommand("RPT", "报表项目", null, null))
        projectService.bindRepository(project.id!!, BindRepositoryCommand("r", "https://git.example.com/r.git", "GITLAB", "main", "t"))
        // Ruling #48: RPT-* codes disjoint from frozen Task 3.1 (SEC) and Task 4.3 (PIPE) in the shared :app-server:test container.
        val standard = checklistService.createStandard("RPT-SEC", "规范", null)
        val checklist = checklistService.createChecklist(standard.id!!, "RPT-BASIC", "基线")
        checklistService.addItem(checklist.id!!, AddItemCommand(itemCode = "RPT-001", name = "防注入", riskLevel = "HIGH"))
        val version = checklistService.publish(checklist.id!!)
        checklistService.bindProject(project.id!!, version.id!!)
        // Ruling #48: RPT-SQLI rule code disjoint from Task 4.3's STUB-SQLI (rule_definition.rule_code is UNIQUE, V5 DDL).
        val rule = ruleService.create(CreateRuleCommand("RPT-SQLI", "注入", "HIGH", null))
        ruleService.addEngineBinding(rule.id!!, AddEngineBindingCommand("STUB", "rpt-rule-sqli", null))
        ruleService.addComplianceMapping(rule.id!!, "RPT-001")
        ruleService.setEvaluationPolicy(rule.id!!, SetPolicyCommand("FAIL", null, "severity == 'HIGH'"))
        ruleService.publish(rule.id!!)

        val task = scanTaskService.startScan(project.id!!, "STUB", "main")
        repeat(50) {
            if (scanTaskService.get(task.id!!).status !in setOf(ScanTaskStatus.PENDING, ScanTaskStatus.PREPARING, ScanTaskStatus.RUNNING)) return@repeat
            Thread.sleep(200)
        }

        // Deviation (documented in task-5.1-report.md): findingCount/bySeverity are asserted >= 1, not
        // exact — the shared container's first scan_task always gets auto-increment id 1, and the frozen
        // Task 3.1 FindingRepositoryIntegrationTest hardcodes scanTaskId = 1L (SEC-001, HIGH). When that
        // class ran earlier in the same JVM (full suite), task 1's scan-summary includes its SEC-001 row
        // too (count=2); when this test runs alone, only the RPT finding exists (count=1). Same class of
        // collision as Ruling #43 / the frozen ScanPipelineIntegrationTest's ruleCode filter — scan-summary's
        // DTO exposes only bySeverity, so it cannot distinguish THIS scan's finding. The compliance-summary
        // and trend assertions below pin down this scan's data per-project.
        mockMvc.perform(get("/api/v1/reports/scan-summary").param("taskId", task.id.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.engine").value("STUB"))
            .andExpect(jsonPath("$.data.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.findingCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.data.bySeverity.HIGH").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))

        mockMvc.perform(get("/api/v1/reports/compliance-summary").param("projectId", project.id.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.failed").value(1))
            .andExpect(jsonPath("$.data.items[0].itemCode").value("RPT-001"))
            .andExpect(jsonPath("$.data.items[0].result").value("FAIL"))

        mockMvc.perform(get("/api/v1/reports/trend").param("projectId", project.id.toString()).param("days", "30"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].failed").value(1))
    }
}
