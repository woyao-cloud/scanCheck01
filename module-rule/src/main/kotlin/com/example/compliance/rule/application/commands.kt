package com.example.compliance.rule.application

data class CreateRuleCommand(
    val ruleCode: String,
    val name: String,
    val riskLevel: String = "MEDIUM",
    val description: String? = null,
)

data class AddEngineBindingCommand(
    val engine: String,
    val engineRuleId: String,
    val engineConfigJson: String? = null,
)

data class SetPolicyCommand(
    val resultOnMatch: String = "FAIL",
    val policyJson: String? = null,
    val spElExpression: String? = null,
)

data class UpdateRuleCommand(
    val name: String? = null,
    val riskLevel: String? = null,
    val description: String? = null,
)
