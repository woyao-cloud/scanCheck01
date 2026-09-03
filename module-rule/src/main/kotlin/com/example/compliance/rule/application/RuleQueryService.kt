package com.example.compliance.rule.application

import com.example.compliance.rule.domain.RuleDefinition
import com.example.compliance.rule.domain.RuleEvaluationPolicy
import com.example.compliance.rule.domain.RuleStatus
import com.example.compliance.rule.infrastructure.RuleComplianceMappingRepository
import com.example.compliance.rule.infrastructure.RuleDefinitionRepository
import com.example.compliance.rule.infrastructure.RuleEvaluationPolicyRepository
import org.springframework.stereotype.Service

/** 供 module-scan 的 ComplianceEvaluator 使用的只读查询。 */
@Service
class RuleQueryService(
    private val ruleRepository: RuleDefinitionRepository,
    private val mappingRepository: RuleComplianceMappingRepository,
    private val policyRepository: RuleEvaluationPolicyRepository,
) {
    /** 按引擎 + 引擎规则号找到已发布规则；同时校验其 engine binding 匹配。
     *  F7 (spec §6.5)：单条 JPQL 连接 binding（engine + engineRuleId + status=PUBLISHED），
     *  替代原 findAll().filter{} 内存过滤 —— 该方法是每扫描逐 finding 调用，绑定多时 O(N²)。 */
    fun publishedRuleByEngineRuleId(engine: String, engineRuleId: String): RuleDefinition? =
        ruleRepository.findFirstByEngineAndEngineRuleIdAndStatus(engine, engineRuleId, RuleStatus.PUBLISHED)

    fun policyByRuleId(ruleId: Long): RuleEvaluationPolicy? = policyRepository.findByRuleId(ruleId)

    fun itemCodesByRuleId(ruleId: Long): List<String> =
        mappingRepository.findByRuleId(ruleId).map { it.checklistItemCode }

    /** 按平台规则号查已发布规则（module-scan 合规判定使用）。 */
    fun findByRuleCode(ruleCode: String): RuleDefinition? =
        ruleRepository.findAll().firstOrNull {
            it.ruleCode == ruleCode && it.status == com.example.compliance.rule.domain.RuleStatus.PUBLISHED
        }
}
