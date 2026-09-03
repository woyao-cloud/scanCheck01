package com.example.compliance.common.auth

import org.springframework.security.core.AuthenticatedPrincipal

/**
 * 认证主体：JwtAuthenticationFilter 从 JWT 解析出的真实用户身份（final review I6）。
 * 实现 [AuthenticatedPrincipal] 使 Authentication.name 保持 username（AuthController /me 兼容）。
 * RemediationController.actorId 从 principal 解析真实 userId；@WithMockUser 等 String principal 回落 1L。
 */
data class AuthPrincipal(
    val userId: Long,
    val username: String,
    val authorities: Set<String>,
) : AuthenticatedPrincipal {
    override fun getName(): String = username

    fun hasRole(role: String): Boolean = "ROLE_$role" in authorities
}
