package com.example.compliance.openapi.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.openapi.domain.ApiToken
import com.example.compliance.openapi.infrastructure.ApiTokenRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Instant

/** OpenAPI 触发 token 服务：BCrypt 哈希、明文仅创建返回一次（P2-D7）。 */
@Service
class ApiTokenService(
    private val repository: ApiTokenRepository,
    private val encoder: PasswordEncoder,
) {
    data class ApiTokenResult(val token: String, val apiToken: ApiToken)

    private val random = SecureRandom()

    /** 创建：明文 token 仅本方法返回一次；库中只存 BCrypt 哈希。 */
    @Transactional
    fun create(name: String, expiresAt: Instant?, createdBy: Long): ApiTokenResult {
        if (repository.existsByName(name)) throw BusinessException(409, "api token name already exists: $name")
        val raw = TOKEN_PREFIX + name + "-" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(randomBytes())
        val saved = repository.save(ApiToken().apply {
            this.name = name
            tokenHash = encoder.encode(raw)
            this.expiresAt = expiresAt
            this.createdBy = createdBy
        })
        return ApiTokenResult(raw, saved)
    }

    /** 校验：明文形如 cop-<name>-<random>，解析 name 找候选，BCrypt 匹配 + ACTIVE + 未过期。 */
    @Transactional(readOnly = true)
    fun verify(rawToken: String): ApiToken? {
        if (!rawToken.startsWith(TOKEN_PREFIX)) return null
        val body = rawToken.removePrefix(TOKEN_PREFIX)
        val name = body.dropLast(TOKEN_RANDOM_CHARS + 1)
        val candidates = repository.findByNameAndStatus(name, "ACTIVE")
        val now = Instant.now()
        return candidates.firstOrNull { c ->
            val expires = c.expiresAt
            (expires == null || expires.isAfter(now)) && encoder.matches(rawToken, c.tokenHash)
        }
    }

    /** 禁用：按 id 找、置 DISABLED、save。 */
    @Transactional
    fun disable(tokenId: Long, actorId: Long): ApiToken {
        val token = repository.findById(tokenId)
            .orElseThrow { BusinessException(404, "api token not found: $tokenId") }
        token.status = "DISABLED"
        return repository.save(token)
    }

    /** 列表：仅返回 ACTIVE 的 token。 */
    @Transactional(readOnly = true)
    fun list(): List<ApiToken> = repository.findAllByStatus("ACTIVE")

    /** 记录最近使用时间（verify 成功后由调用方触发；不存在时静默无操作）。 */
    @Transactional
    fun recordUsage(tokenId: Long) {
        repository.findById(tokenId).ifPresent {
            it.lastUsedAt = Instant.now()
            repository.save(it)
        }
    }

    private fun randomBytes(): ByteArray = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }

    companion object {
        private const val TOKEN_BYTES = 24
        private const val TOKEN_PREFIX = "cop-"
        // 24 bytes -> base64url withoutPadding -> exactly 32 chars
        private const val TOKEN_RANDOM_CHARS = 32
    }
}
