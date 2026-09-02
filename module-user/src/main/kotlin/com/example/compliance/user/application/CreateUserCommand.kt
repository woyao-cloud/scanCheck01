package com.example.compliance.user.application

data class CreateUserCommand(
    val username: String,
    val password: String,
    val displayName: String?,
    val email: String?,
    val roleCodes: List<String>,
)
