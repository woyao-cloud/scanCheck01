package com.example.compliance.auth.api

import com.example.compliance.auth.api.dto.LoginRequest
import com.example.compliance.auth.api.dto.LoginResponse
import com.example.compliance.auth.application.AuthService
import com.example.compliance.auth.application.JwtService
import com.example.compliance.common.api.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val jwtService: JwtService,
) {
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ApiResponse<LoginResponse> {
        val token = authService.login(request.username, request.password)
        return ApiResponse.ok(LoginResponse(token, "Bearer", jwtService.expirationMinutes()))
    }

    @GetMapping("/me")
    fun me(): ApiResponse<Map<String, String?>> {
        val auth: Authentication? = SecurityContextHolder.getContext().authentication
        return ApiResponse.ok(
            mapOf("username" to auth?.name, "roles" to auth?.authorities?.joinToString(",") { it.authority })
        )
    }

    @PostMapping("/logout")
    fun logout(): ApiResponse<Unit> {
        SecurityContextHolder.clearContext()
        return ApiResponse.ok()
    }
}
