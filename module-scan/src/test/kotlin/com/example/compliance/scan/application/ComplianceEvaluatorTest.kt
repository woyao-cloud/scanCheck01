package com.example.compliance.scan.application

import com.example.compliance.checklist.application.ChecklistQueryService
import com.example.compliance.checklist.domain.ChecklistItem
import com.example.compliance.checklist.domain.ItemResult
import com.example.compliance.result.domain.Finding
import com.example.compliance.rule.application.RuleQueryService
import com.example.compliance.rule.domain.RuleDefinition
import com.example.compliance.rule.domain.RuleEvaluationPolicy
import com.example.compliance.rule.domain.RuleStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ComplianceEvaluatorTest {
    private val ruleQuery = mockk<RuleQueryService>(relaxed = true)
    private val checklistQuery = mockk<ChecklistQueryService>(relaxed = true)
    private val evaluator = ComplianceEvaluator(ruleQuery, checklistQuery)

    private fun finding(code: String, severity: String) = Finding().apply {
        id = 1L; ruleCode = code; filePath = "A.java"; lineNumber = 1; this.severity = severity
    }

    @Test
    fun `HIGH finding mapped to FAIL policy produces FAIL item result`() {
        val rule = RuleDefinition().apply { id = 1L; ruleCode = "SEMGREP-SQLI"; status = RuleStatus.PUBLISHED }
        every { checklistQuery.publishedItemsForProject(1L) } returns
            listOf(ChecklistItem().apply { itemCode = "SEC-001" })
        every { ruleQuery.findByRuleCode("SEMGREP-SQLI") } returns rule
        every { ruleQuery.policyByRuleId(1L) } returns
            RuleEvaluationPolicy().apply { resultOnMatch = "FAIL"; spElExpression = "severity == 'HIGH'" }
        every { ruleQuery.itemCodesByRuleId(1L) } returns listOf("SEC-001")

        val result = evaluator.evaluate(1L, null, listOf(finding("SEMGREP-SQLI", "HIGH")))
        assertEquals(1, result.size)
        assertEquals("SEC-001", result[0].itemCode)
        assertEquals(ItemResult.FAIL, result[0].result)
        assertEquals(1, result[0].findingCount)
    }

    @Test
    fun `MEDIUM finding does not match HIGH-only policy and passes`() {
        val rule = RuleDefinition().apply { id = 1L; ruleCode = "SEMGREP-SQLI"; status = RuleStatus.PUBLISHED }
        every { checklistQuery.publishedItemsForProject(1L) } returns
            listOf(ChecklistItem().apply { itemCode = "SEC-001" })
        every { ruleQuery.findByRuleCode("SEMGREP-SQLI") } returns rule
        every { ruleQuery.policyByRuleId(1L) } returns
            RuleEvaluationPolicy().apply { resultOnMatch = "FAIL"; spElExpression = "severity == 'HIGH'" }
        every { ruleQuery.itemCodesByRuleId(1L) } returns listOf("SEC-001")

        val result = evaluator.evaluate(1L, null, listOf(finding("SEMGREP-SQLI", "MEDIUM")))
        assertEquals(ItemResult.PASS, result[0].result)
    }

    @Test
    fun `evaluate uses version items when checklistVersionId provided`() {
        val item = ChecklistItem().apply { itemCode = "M6-001"; versionId = 77L }
        every { checklistQuery.versionItems(77L) } returns listOf(item)
        every { ruleQuery.findByRuleCode("R1") } returns RuleDefinition().apply { id = 1L; ruleCode = "R1" }
        every { ruleQuery.policyByRuleId(1L) } returns RuleEvaluationPolicy().apply { spElExpression = "severity == 'HIGH'" }
        every { ruleQuery.itemCodesByRuleId(1L) } returns listOf("M6-001")
        val finding = Finding().apply { id = 9L; ruleCode = "R1"; severity = "HIGH" }

        val result = evaluator.evaluate(9L, 77L, listOf(finding))

        assertEquals(1, result.size)
        assertEquals("M6-001", result[0].itemCode)
        verify { checklistQuery.versionItems(77L) }
    }
}
