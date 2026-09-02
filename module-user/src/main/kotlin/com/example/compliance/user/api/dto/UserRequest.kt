package com.example.compliance.user.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class UserRequest(
    @field:NotBlank @field:Size(max = 64) val username: String,
    @field:NotBlank @field:Size(min = 6, max = 64) val password: String,
    val displayName: String? = null,
    val email: String? = null,
    val roleCodes: List<String> = emptyList(),
)
