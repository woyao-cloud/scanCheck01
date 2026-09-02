package com.example.compliance.user.api.dto

data class UserResponse(
    val id: Long,
    val username: String,
    val displayName: String?,
    val email: String?,
    val status: String,
)
