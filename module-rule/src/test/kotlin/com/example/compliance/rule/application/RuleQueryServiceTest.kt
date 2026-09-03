package com.example.compliance.rule.application

import com.example.compliance.rule.domain.RuleDefinition
import com.example.compliance.rule.domain.RuleStatus
import com.example.compliance.rule.infrastructure.RuleComplianceMappingRepository
import com.example.compliance.rule.infrastructure.RuleDefinitionRepository
import com.example.compliance.rule.infrastructure.RuleEvaluationPolicyRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** F7 (spec §6.5)：publishedRuleByEngineRuleId 走单条 JPQL（engine + engineRuleId + PUBLISHED），
 *  不再 findAll 内存过滤。严格 mock 下若实现误调 findAll/findAllById 会直接抛（未 stub）。 */
class RuleQueryServiceTest {
    private val ruleRepository = mockk<RuleDefinitionRepository>()
    private val mappingRepository = mockk<RuleComplianceMappingRepository>()
    private val policyRepository = mockk<RuleEvaluationPolicyRepository>()
    private val service = RuleQueryService(ruleRepository, mappingRepository, policyRepository)

    @Test
    fun `publishedRuleByEngineRuleId delegates to indexed JPQL and returns published rule`() {
        val rule = RuleDefinition().apply { id = 1L; ruleCode = "R1"; status = RuleStatus.PUBLISHED }
        every {
            ruleRepository.findFirstByEngineAndEngineRuleIdAndStatus("SEMGREP", "java.lang.x", RuleStatus.PUBLISHED)
        } returns rule

        assertEquals(rule, service.publishedRuleByEngineRuleId("SEMGREP", "java.lang.x"))
        verify(exactly = 0) { ruleRepository.findAll() }
        verify(exactly = 0) { ruleRepository.findAllById(any()) }
    }

    @Test
    fun `publishedRuleByEngineRuleId returns null when no published rule matches`() {
        every {
            ruleRepository.findFirstByEngineAndEngineRuleIdAndStatus("SEMGREP", "no-such", RuleStatus.PUBLISHED)
        } returns null

        assertNull(service.publishedRuleByEngineRuleId("SEMGREP", "no-such"))
        verify(exactly = 0) { ruleRepository.findAll() }
    }

    // M10 清理①：findByRuleCode 原为 findAll().firstOrNull{} 内存过滤（每扫描逐调用 O(N)）。
    // 改委托派生查询后：严格 mock 下若实现误调 findAll 会直接抛（未 stub）。
    @Test
    fun `findByRuleCode delegates to JPQL and never scans all rules`() {
        every { ruleRepository.findFirstByRuleCodeAndStatus("R1", RuleStatus.PUBLISHED) } returns published("R1")
        val result = service.findByRuleCode("R1")
        assertEquals("R1", result?.ruleCode)
        verify(exactly = 0) { ruleRepository.findAll() }
        verify(exactly = 1) { ruleRepository.findFirstByRuleCodeAndStatus("R1", RuleStatus.PUBLISHED) }
    }

    @Test
    fun `findByRuleCode returns null when no published rule matches`() {
        every { ruleRepository.findFirstByRuleCodeAndStatus("R1", RuleStatus.PUBLISHED) } returns null

        assertNull(service.findByRuleCode("R1"))
        verify(exactly = 0) { ruleRepository.findAll() }
    }

    private fun published(ruleCode: String) = RuleDefinition().apply {
        this.ruleCode = ruleCode; status = RuleStatus.PUBLISHED
    }
}
