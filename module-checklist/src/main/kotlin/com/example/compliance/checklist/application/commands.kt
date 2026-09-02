package com.example.compliance.checklist.application

data class AddItemCommand(
    val itemCode: String,
    val name: String,
    val category: String? = null,
    val riskLevel: String = "MEDIUM",
    val description: String? = null,
    val basis: String? = null,
    val remediation: String? = null,
    val required: Boolean = true,
    val waivable: Boolean = false,
    val scoreWeight: java.math.BigDecimal = java.math.BigDecimal.ONE,
)
