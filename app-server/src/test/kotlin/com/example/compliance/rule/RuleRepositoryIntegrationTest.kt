package com.example.compliance.rule

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.rule.domain.RuleComplianceMapping
import com.example.compliance.rule.domain.RuleDefinition
import com.example.compliance.rule.domain.RuleEngineBinding
import com.example.compliance.rule.domain.RuleEvaluationPolicy
import com.example.compliance.rule.domain.RuleStatus
import com.example.compliance.rule.infrastructure.RuleDefinitionRepository
import com.example.compliance.rule.infrastructure.RuleEngineBindingRepository
import com.example.compliance.rule.infrastructure.RuleComplianceMappingRepository
import com.example.compliance.rule.infrastructure.RuleEvaluationPolicyRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals

class RuleRepositoryIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var ruleRepository: RuleDefinitionRepository
    @Autowired lateinit var bindingRepository: RuleEngineBindingRepository
    @Autowired lateinit var mappingRepository: RuleComplianceMappingRepository
    @Autowired lateinit var policyRepository: RuleEvaluationPolicyRepository

    @Test
    fun `rule with binding mapping policy persists and queries`() {
        val rule = ruleRepository.save(RuleDefinition().apply {
            ruleCode = "SEMGREP-JAVA-SQLI"; name = "SQL注入"; riskLevel = "HIGH"; status = RuleStatus.PUBLISHED
        })
        bindingRepository.save(RuleEngineBinding().apply {
            ruleId = rule.id!!; engine = "SEMGREP"; engineRuleId = "java.lang.security.audit.sql-injection"
        })
        mappingRepository.save(RuleComplianceMapping().apply {
            ruleId = rule.id!!; checklistItemCode = "SEC-001"
        })
        policyRepository.save(RuleEvaluationPolicy().apply {
            ruleId = rule.id!!; resultOnMatch = "FAIL"; spElExpression = "severity == 'ERROR'"
        })

        val byEngine = ruleRepository.findFirstByStatusAndEngineRuleId(RuleStatus.PUBLISHED, "java.lang.security.audit.sql-injection")
        assertEquals(rule.id, byEngine?.id)
        assertEquals(1, mappingRepository.findByRuleId(rule.id!!).size)
        assertEquals("FAIL", policyRepository.findByRuleId(rule.id!!)?.resultOnMatch)
    }
}
