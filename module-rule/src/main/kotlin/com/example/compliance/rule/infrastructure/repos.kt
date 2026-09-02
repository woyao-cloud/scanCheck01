package com.example.compliance.rule.infrastructure

import com.example.compliance.rule.domain.RuleComplianceMapping
import com.example.compliance.rule.domain.RuleDefinition
import com.example.compliance.rule.domain.RuleEngineBinding
import com.example.compliance.rule.domain.RuleEvaluationPolicy
import com.example.compliance.rule.domain.RuleStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface RuleDefinitionRepository : JpaRepository<RuleDefinition, Long> {
    fun existsByRuleCode(ruleCode: String): Boolean
    // Task 3.3 deviation: the brief's derived query `findFirstByStatusAndEngineRuleId` cannot be
    // created — RuleDefinition has no `engineRuleId` property (it lives on RuleEngineBinding), so
    // Spring Data fails at context startup with PropertyReferenceException. Replaced with an explicit
    // JPQL join across the binding table, keeping the brief's method name/signature (used verbatim by
    // RuleRepositoryIntegrationTest) and "first" semantics via HQL order-by + limit (Hibernate 6).
    @Query(
        """
        select r from RuleDefinition r
        join RuleEngineBinding b on b.ruleId = r.id
        where r.status = :status and b.engineRuleId = :engineRuleId
        order by r.id
        limit 1
        """
    )
    fun findFirstByStatusAndEngineRuleId(status: RuleStatus, engineRuleId: String): RuleDefinition?
    fun findByStatus(status: RuleStatus): List<RuleDefinition>
}

interface RuleEngineBindingRepository : JpaRepository<RuleEngineBinding, Long> {
    fun findByRuleId(ruleId: Long): List<RuleEngineBinding>
}

interface RuleComplianceMappingRepository : JpaRepository<RuleComplianceMapping, Long> {
    fun findByRuleId(ruleId: Long): List<RuleComplianceMapping>
    fun findByChecklistItemCodeIn(codes: Collection<String>): List<RuleComplianceMapping>
}

interface RuleEvaluationPolicyRepository : JpaRepository<RuleEvaluationPolicy, Long> {
    fun findByRuleId(ruleId: Long): RuleEvaluationPolicy?
}
