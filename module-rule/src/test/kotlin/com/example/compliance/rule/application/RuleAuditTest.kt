package com.example.compliance.rule.application

import com.example.compliance.common.audit.AuditService
import com.example.compliance.rule.domain.RuleDefinition
import com.example.compliance.rule.domain.RuleStatus
import com.example.compliance.rule.infrastructure.RuleComplianceMappingRepository
import com.example.compliance.rule.infrastructure.RuleDefinitionRepository
import com.example.compliance.rule.infrastructure.RuleEngineBindingRepository
import com.example.compliance.rule.infrastructure.RuleEvaluationPolicyRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.Optional

/** M9：规则写操作审计（审计/回滚闭环的写侧）。 */
class RuleAuditTest {
    private val ruleRepository = mockk<RuleDefinitionRepository>(relaxed = true)
    private val bindingRepository = mockk<RuleEngineBindingRepository>(relaxed = true)
    private val mappingRepository = mockk<RuleComplianceMappingRepository>(relaxed = true)
    private val policyRepository = mockk<RuleEvaluationPolicyRepository>(relaxed = true)
    private val auditService = mockk<AuditService>(relaxed = true)
    private val service = RuleService(ruleRepository, bindingRepository, mappingRepository, policyRepository, auditService)

    @Test
    fun `publish writes audit record`() {
        every { ruleRepository.findById(1L) } returns Optional.of(
            RuleDefinition().apply { id = 1L; ruleCode = "R1"; status = RuleStatus.DRAFT }
        )
        every { ruleRepository.save(any()) } answers { firstArg<RuleDefinition>() }

        service.publish(1L)

        // PF-9 真实签名：record(action, module, userId, resourceType, resourceId, detail, ip)
        verify { auditService.record("RULE_PUBLISHED", "rule", 1L, "rule", 1L, any()) }
    }
}
