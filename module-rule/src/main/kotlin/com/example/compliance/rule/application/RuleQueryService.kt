package com.example.compliance.rule.application

import com.example.compliance.rule.domain.RuleDefinition
import com.example.compliance.rule.domain.RuleEvaluationPolicy
import com.example.compliance.rule.infrastructure.RuleComplianceMappingRepository
import com.example.compliance.rule.infrastructure.RuleDefinitionRepository
import com.example.compliance.rule.infrastructure.RuleEngineBindingRepository
import com.example.compliance.rule.infrastructure.RuleEvaluationPolicyRepository
import org.springframework.stereotype.Service

/** 供 module-scan 的 ComplianceEvaluator 使用的只读查询。 */
@Service
class RuleQueryService(
    private val ruleRepository: RuleDefinitionRepository,
    private val bindingRepository: RuleEngineBindingRepository,
    private val mappingRepository: RuleComplianceMappingRepository,
    private val policyRepository: RuleEvaluationPolicyRepository,
) {
    /** 按引擎 + 引擎规则号找到已发布规则；同时校验其 engine binding 匹配。 */
    fun publishedRuleByEngineRuleId(engine: String, engineRuleId: String): RuleDefinition? {
        val ruleIds = bindingRepository.findAll()
            .filter { it.engine == engine && it.engineRuleId == engineRuleId }
            .map { it.ruleId }
            .toSet()
        if (ruleIds.isEmpty()) return null
        return ruleRepository.findAllById(ruleIds).firstOrNull { it.status == com.example.compliance.rule.domain.RuleStatus.PUBLISHED }
    }

    fun policyByRuleId(ruleId: Long): RuleEvaluationPolicy? = policyRepository.findByRuleId(ruleId)

    fun itemCodesByRuleId(ruleId: Long): List<String> =
        mappingRepository.findByRuleId(ruleId).map { it.checklistItemCode }

    /** 按平台规则号查已发布规则（module-scan 合规判定使用）。 */
    fun findByRuleCode(ruleCode: String): RuleDefinition? =
        ruleRepository.findAll().firstOrNull {
            it.ruleCode == ruleCode && it.status == com.example.compliance.rule.domain.RuleStatus.PUBLISHED
        }
}
