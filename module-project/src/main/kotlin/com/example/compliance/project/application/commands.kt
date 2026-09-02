package com.example.compliance.project.application

data class CreateProjectCommand(
    val code: String,
    val name: String,
    val description: String?,
    val ownerUserId: Long?,
)

data class BindRepositoryCommand(
    val name: String,
    val gitUrl: String,
    val provider: String,
    val defaultBranch: String,
    val credential: String?,
)
