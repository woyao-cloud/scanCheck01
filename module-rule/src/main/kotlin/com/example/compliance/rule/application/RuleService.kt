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
        val saved = ruleRepository.save(RuleDefinition().apply {
            ruleCode = command.ruleCode
            name = command.name
            riskLevel = command.riskLevel
            description = command.description
            status = RuleStatus.DRAFT
        })
        auditService.record(
            "RULE_CREATED", "rule", 1L, "rule",
            saved.id, """{"ruleCode":"${saved.ruleCode}","riskLevel":"${saved.riskLevel}"}""",
        )
        return saved
    }

    private fun require(ruleId: Long): RuleDefinition =
        ruleRepository.findById(ruleId).orElseThrow { BusinessException(404, "rule not found: $ruleId") }

    @Transactional
    fun addEngineBinding(ruleId: Long, command: AddEngineBindingCommand): RuleEngineBinding {
        require(ruleId)
        val saved = bindingRepository.save(RuleEngineBinding().apply {
            this.ruleId = ruleId
            engine = command.engine
            engineRuleId = command.engineRuleId
            engineConfigJson = command.engineConfigJson
        })
        auditService.record(
            "RULE_ENGINE_BIND", "rule", 1L, "rule",
            saved.id, """{"ruleId":$ruleId,"engine":"${saved.engine}"}""",
        )
        return saved
    }

    @Transactional
    fun addComplianceMapping(ruleId: Long, checklistItemCode: String): RuleComplianceMapping {
        require(ruleId)
        val saved = mappingRepository.save(RuleComplianceMapping().apply {
            this.ruleId = ruleId
            this.checklistItemCode = checklistItemCode
        })
        auditService.record(
            "RULE_MAPPING", "rule", 1L, "rule",
            saved.id, """{"ruleId":$ruleId,"checklistItemCode":"$checklistItemCode"}""",
        )
        return saved
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
        val saved = policyRepository.save(policy)
        auditService.record(
            "RULE_POLICY_SET", "rule", 1L, "rule",
            saved.id, """{"ruleId":$ruleId,"resultOnMatch":"${saved.resultOnMatch}"}""",
        )
        return saved
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
        val saved = ruleRepository.save(rule)
        auditService.record(
            "RULE_UPDATED", "rule", 1L, "rule",
            saved.id, """{"ruleCode":"${saved.ruleCode}"}""",
        )
        return saved
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
            "RULE_PUBLISHED", "rule", 1L, "rule",
            // Ruling #34: audit_log.detail is JSONB (V1 DDL) — detail must be valid JSON.
            saved.id, """{"rule":"${saved.ruleCode}"}""",
        )
        return saved
    }

    @Transactional
    fun disable(ruleId: Long): RuleDefinition {
        val rule = require(ruleId)
        rule.status = RuleStatus.DISABLED
        val saved = ruleRepository.save(rule)
        auditService.record(
            "RULE_DISABLE", "rule", 1L, "rule_definition",
            // Ruling #34: audit_log.detail is JSONB (V1 DDL) — detail must be valid JSON.
            saved.id, """{"rule":"${saved.ruleCode}"}""",
        )
        return saved
    }
}
