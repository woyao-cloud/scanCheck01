package com.example.compliance.rule.api.dto

import com.example.compliance.rule.domain.RuleDefinition
import java.time.Instant

data class RuleResponse(
    val id: Long, val ruleCode: String, val name: String,
    val riskLevel: String, val status: String, val description: String?,
) {
    companion object { fun from(r: RuleDefinition) = RuleResponse(r.id!!, r.ruleCode, r.name, r.riskLevel, r.status.name, r.description) }
}

/** P0：规则版本号取自 @Version 乐观锁字段（当前行即最新版本），完整版本历史留 P1。 */
data class RuleVersionResponse(
    val ruleId: Long, val version: Long, val status: String, val updatedAt: Instant?,
) {
    companion object { fun from(r: RuleDefinition) = RuleVersionResponse(r.id!!, r.version, r.status.name, r.updatedAt) }
}
