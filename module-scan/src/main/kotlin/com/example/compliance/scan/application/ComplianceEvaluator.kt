package com.example.compliance.scan.application

import com.example.compliance.checklist.application.ChecklistQueryService
import com.example.compliance.checklist.domain.ItemResult
import com.example.compliance.result.domain.Finding
import com.example.compliance.rule.application.RuleQueryService
import com.example.compliance.rule.domain.RuleEvaluationPolicy
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.stereotype.Component

@Component
class ComplianceEvaluator(
    private val ruleQueryService: RuleQueryService,
    private val checklistQueryService: ChecklistQueryService,
) {
    private val parser = SpelExpressionParser()

    data class ItemEvaluation(
        val itemCode: String,
        val result: ItemResult,
        val findingCount: Int,
        val matchedFindingIds: List<Long>,
    )

    /** 对一次扫描的 findings 做合规判定：优先按版本解析清单条目；versionId 为 null 时回退到项目当前已发布绑定。 */
    fun evaluate(projectId: Long, checklistVersionId: Long?, findings: List<Finding>): List<ItemEvaluation> {
        val items = checklistVersionId
            ?.let { checklistQueryService.versionItems(it) }
            ?: checklistQueryService.publishedItemsForProject(projectId) ?: return emptyList()
        val itemCodes = items.map { it.itemCode }.toSet()
        val evaluations = mutableListOf<ItemEvaluation>()

        for ((ruleCode, ruleFindings) in findings.groupBy { it.ruleCode }) {
            val rule = ruleQueryService.findByRuleCode(ruleCode) ?: continue
            val policy = ruleQueryService.policyByRuleId(rule.id!!) ?: continue
            val mappedItems = ruleQueryService.itemCodesByRuleId(rule.id!!).filter { it in itemCodes }
            if (mappedItems.isEmpty()) continue

            val matched = ruleFindings.filter { evaluatePolicy(policy, it) }
            val result = if (matched.isNotEmpty()) ItemResult.valueOf(policy.resultOnMatch) else ItemResult.PASS
            val findingIds = matched.map { it.id!! }
            mappedItems.forEach { itemCode ->
                evaluations += ItemEvaluation(itemCode, result, findingIds.size, findingIds)
            }
        }
        return evaluations
    }

    private fun evaluatePolicy(policy: RuleEvaluationPolicy, finding: Finding): Boolean {
        val expr = policy.spElExpression
        if (expr.isNullOrBlank()) return false
        return runCatching {
            parser.parseExpression(expr).getValue(finding, Boolean::class.java) ?: false
        }.getOrDefault(false)
    }
}
