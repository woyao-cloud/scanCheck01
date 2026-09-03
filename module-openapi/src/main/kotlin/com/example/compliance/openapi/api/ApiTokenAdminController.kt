package com.example.compliance.openapi.api

import com.example.compliance.openapi.application.ApiTokenService
import com.example.compliance.openapi.domain.ApiToken
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

/** OpenAPI token 管理（ADMIN）。创建响应中仅返回一次明文 token。 */
@RestController
@RequestMapping("/api/v1/openapi/tokens")
class ApiTokenAdminController(private val service: ApiTokenService) {

    data class CreateTokenCommand(val name: String, val expiresAt: java.time.Instant? = null)
    data class TokenView(val id: Long, val name: String, val status: String, val expiresAt: java.time.Instant?, val token: String?)

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    fun create(@RequestBody cmd: CreateTokenCommand): TokenView {
        val result = service.create(cmd.name, cmd.expiresAt, 1L)
        return TokenView(result.apiToken.id!!, result.apiToken.name, result.apiToken.status, result.apiToken.expiresAt, result.token)
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    fun list(): List<TokenView> = service.list().map { TokenView(it.id!!, it.name, it.status, it.expiresAt, null) }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    fun disable(@PathVariable id: Long): TokenView {
        val t = service.disable(id, 1L)
        return TokenView(t.id!!, t.name, t.status, t.expiresAt, null)
    }
}
