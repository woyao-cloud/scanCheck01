package com.example.compliance.rule.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.rule.domain.RuleDefinition
import com.example.compliance.rule.domain.RuleEngineBinding
import com.example.compliance.rule.domain.RuleComplianceMapping
import com.example.compliance.rule.domain.RuleEvaluationPolicy
import com.example.compliance.rule.domain.RuleStatus
import com.example.compliance.rule.infrastructure.RuleComplianceMappingRepository
import com.example.compliance.rule.infrastructure.RuleDefinitionRepository
import com.example.compliance.rule.infrastructure.RuleEngineBindingRepository
import com.example.compliance.rule.infrastructure.RuleEvaluationPolicyRepository
import com.example.compliance.common.audit.AuditService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RuleService(
    private val ruleRepository: RuleDefinitionRepository,
    private val bindingRepository: RuleEngineBindingRepository,
    private val mappingRepository: RuleComplianceMappingRepository,
    private val policyRepository: RuleEvaluationPolicyRepository,
    private val auditService: AuditService,
) {
    @Transactional
    fun create(command: CreateRuleCommand): RuleDefinition {
        if (ruleRepository.existsByRuleCode(command.ruleCode)) {
            throw BusinessException(400, "rule code already exists: ${command.ruleCode}")
        }
        return ruleRepository.save(RuleDefinition().apply {
            ruleCode = command.ruleCode
            name = command.name
            riskLevel = command.riskLevel
            description = command.description
            status = RuleStatus.DRAFT
        })
    }

    private fun require(ruleId: Long): RuleDefinition =
        ruleRepository.findById(ruleId).orElseThrow { BusinessException(404, "rule not found: $ruleId") }

    @Transactional
    fun addEngineBinding(ruleId: Long, command: AddEngineBindingCommand): RuleEngineBinding {
        require(ruleId)
        return bindingRepository.save(RuleEngineBinding().apply {
            this.ruleId = ruleId
            engine = command.engine
            engineRuleId = command.engineRuleId
            engineConfigJson = command.engineConfigJson
        })
    }

    @Transactional
    fun addComplianceMapping(ruleId: Long, checklistItemCode: String): RuleComplianceMapping {
        require(ruleId)
        return mappingRepository.save(RuleComplianceMapping().apply {
            this.ruleId = ruleId
            this.checklistItemCode = checklistItemCode
        })
    }

    @Transactional
    fun setEvaluationPolicy(ruleId: Long, command: SetPolicyCommand): RuleEvaluationPolicy {
        require(ruleId)
        val policy = policyRepository.findByRuleId(ruleId) ?: RuleEvaluationPolicy().apply { this.ruleId = ruleId }
        if (command.resultOnMatch == "FAIL" && command.spElExpression.isNullOrBlank()) {
            throw BusinessException(400, "FAIL policy requires spElExpression")
        }
        policy.resultOnMatch = command.resultOnMatch
        policy.policyJson = command.policyJson
        policy.spElExpression = command.spElExpression
        return policyRepository.save(policy)
    }

    fun list(): List<RuleDefinition> = ruleRepository.findAll()

    fun get(ruleId: Long): RuleDefinition = require(ruleId)

    /** 更新规则元信息（ruleCode 不可变，改 code 视为新建）。 */
    @Transactional
    fun update(ruleId: Long, command: UpdateRuleCommand): RuleDefinition {
        val rule = require(ruleId)
        command.name?.let { rule.name = it }
        command.riskLevel?.let { rule.riskLevel = it }
        command.description?.let { rule.description = it }
        return ruleRepository.save(rule)
    }

    @Transactional
    fun publish(ruleId: Long): RuleDefinition {
        val rule = require(ruleId)
        if (rule.status !in setOf(RuleStatus.DRAFT, RuleStatus.TESTING)) {
            throw BusinessException(400, "only DRAFT/TESTING rule can be published, current: ${rule.status}")
        }
        rule.status = RuleStatus.PUBLISHED
        val saved = ruleRepository.save(rule)
        auditService.record(
            "RULE_PUBLISH", "rule", null, "rule_definition",
            // Ruling #34: audit_log.detail is JSONB (V1 DDL) — plain-text detail fails INSERT with
            // "invalid input syntax for type json" → 500. Same defect as Task 3.2's publish/bindProject.
            saved.id, """{"rule":"${saved.ruleCode}"}""", null,
        )
        return saved
    }

    @Transactional
    fun disable(ruleId: Long): RuleDefinition {
        val rule = require(ruleId)
        rule.status = RuleStatus.DISABLED
        val saved = ruleRepository.save(rule)
        auditService.record(
            "RULE_DISABLE", "rule", null, "rule_definition",
            // Ruling #34: audit_log.detail is JSONB (V1 DDL) — plain-text detail fails INSERT with
            // "invalid input syntax for type json" → 500. Same defect as Task 3.2's publish/bindProject.
            saved.id, """{"rule":"${saved.ruleCode}"}""", null,
        )
        return saved
    }
}
