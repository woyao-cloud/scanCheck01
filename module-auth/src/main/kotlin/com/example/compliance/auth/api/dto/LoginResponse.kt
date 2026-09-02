package com.example.compliance.auth.api.dto

data class LoginResponse(
    val token: String,
    val tokenType: String = "Bearer",
    val expiresInMinutes: Long,
)
