package com.example.compliance.rule.api.dto

import com.example.compliance.rule.application.AddEngineBindingCommand
import com.example.compliance.rule.application.CreateRuleCommand
import com.example.compliance.rule.application.SetPolicyCommand
import jakarta.validation.constraints.NotBlank

data class RuleRequest(
    @field:NotBlank val ruleCode: String,
    @field:NotBlank val name: String,
    val riskLevel: String = "MEDIUM",
    val description: String? = null,
) { fun toCommand() = CreateRuleCommand(ruleCode, name, riskLevel, description) }

data class EngineBindingRequest(
    @field:NotBlank val engine: String,
    @field:NotBlank val engineRuleId: String,
    val engineConfigJson: String? = null,
) { fun toCommand() = AddEngineBindingCommand(engine, engineRuleId, engineConfigJson) }

data class PolicyRequest(
    val resultOnMatch: String = "FAIL",
    val policyJson: String? = null,
    val spElExpression: String? = null,
) { fun toCommand() = SetPolicyCommand(resultOnMatch, policyJson, spElExpression) }

data class MappingRequest(@field:NotBlank val checklistItemCode: String)

data class UpdateRuleRequest(
    val name: String? = null,
    val riskLevel: String? = null,
    val description: String? = null,
) { fun toCommand() = com.example.compliance.rule.application.UpdateRuleCommand(name, riskLevel, description) }
