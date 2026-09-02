package com.example.compliance.rule.application

import com.example.compliance.common.audit.AuditService
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.rule.domain.RuleDefinition
import com.example.compliance.rule.domain.RuleStatus
import com.example.compliance.rule.infrastructure.RuleDefinitionRepository
import com.example.compliance.rule.infrastructure.RuleEngineBindingRepository
import com.example.compliance.rule.infrastructure.RuleComplianceMappingRepository
import com.example.compliance.rule.infrastructure.RuleEvaluationPolicyRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuleServiceTest {
    private val ruleRepository = mockk<RuleDefinitionRepository>(relaxed = true)
    private val bindingRepository = mockk<RuleEngineBindingRepository>(relaxed = true)
    private val mappingRepository = mockk<RuleComplianceMappingRepository>(relaxed = true)
    private val policyRepository = mockk<RuleEvaluationPolicyRepository>(relaxed = true)
    private val auditService = mockk<AuditService>(relaxed = true)
    private val service = RuleService(ruleRepository, bindingRepository, mappingRepository, policyRepository, auditService)

    @Test
    fun `create rule starts as DRAFT`() {
        every { ruleRepository.existsByRuleCode("R1") } returns false
        every { ruleRepository.save(any()) } answers { firstArg<RuleDefinition>().apply { id = 1L } }
        val rule = service.create(CreateRuleCommand("R1", "规则一", "HIGH", "desc"))
        assertEquals(RuleStatus.DRAFT, rule.status)
    }

    @Test
    fun `publish only from DRAFT or TESTING`() {
        every { ruleRepository.findById(1L) } returns Optional.of(
            RuleDefinition().apply { id = 1L; ruleCode = "R1"; status = RuleStatus.PUBLISHED }
        )
        assertFailsWith<BusinessException> { service.publish(1L) }
    }

    @Test
    fun `setEvaluationPolicy requires spEl for FAIL mapping`() {
        every { ruleRepository.findById(1L) } returns Optional.of(
            RuleDefinition().apply { id = 1L; ruleCode = "R1"; status = RuleStatus.DRAFT }
        )
        assertFailsWith<BusinessException> {
            service.setEvaluationPolicy(1L, SetPolicyCommand("FAIL", null, null))
        }
    }
}
