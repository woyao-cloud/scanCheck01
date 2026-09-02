package com.example.compliance.project.api.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ProjectRequest(
    @field:NotBlank @field:Size(max = 64) val code: String,
    @field:NotBlank @field:Size(max = 128) val name: String,
    val description: String? = null,
    val ownerUserId: Long? = null,
)

data class RepositoryRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val gitUrl: String,
    @field:NotBlank val provider: String,
    val defaultBranch: String = "main",
    val credential: String? = null,
)
