package com.example.compliance.scan.api.dto

import jakarta.validation.constraints.NotBlank

data class ScanRequest(
    @field:NotBlank val engine: String,
    val ref: String? = null,
)
