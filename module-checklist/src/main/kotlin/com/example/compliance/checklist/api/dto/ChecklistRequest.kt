package com.example.compliance.checklist.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal

data class StandardRequest(
    @field:NotBlank @field:Size(max = 64) val code: String,
    @field:NotBlank @field:Size(max = 128) val name: String,
    val description: String? = null,
)

data class ChecklistRequest(
    val standardId: Long,
    @field:NotBlank @field:Size(max = 64) val code: String,
    @field:NotBlank @field:Size(max = 128) val name: String,
)

data class ChecklistItemRequest(
    @field:NotBlank @field:Size(max = 64) val itemCode: String,
    @field:NotBlank @field:Size(max = 128) val name: String,
    val category: String? = null,
    val riskLevel: String = "MEDIUM",
    val description: String? = null,
    val basis: String? = null,
    val remediation: String? = null,
    val required: Boolean = true,
    val waivable: Boolean = false,
    val scoreWeight: BigDecimal = BigDecimal.ONE,
)

data class BindRequest(val checklistVersionId: Long)
