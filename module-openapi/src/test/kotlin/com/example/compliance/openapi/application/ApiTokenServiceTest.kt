package com.example.compliance.openapi.application

import com.example.compliance.openapi.domain.ApiToken
import com.example.compliance.openapi.infrastructure.ApiTokenRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M9：OpenAPI token 多 CI 管理（P2-D7）——BCrypt 哈希、明文仅创建返回一次。 */
class ApiTokenServiceTest {

    private val repository = mockk<ApiTokenRepository>()
    private val encoder = BCryptPasswordEncoder()
    private val service = ApiTokenService(repository, encoder)

    @Test
    fun `create returns plaintext once and stores hash`() {
        every { repository.existsByName("ci-a") } returns false
        every { repository.save(any<ApiToken>()) } answers { (firstArg<ApiToken>()).also { it.id = 5L } }

        val result = service.create("ci-a", null, 9L)

        assertNotNull(result.token)
        assertTrue(result.token.length >= 20)
        verify { repository.save(match { it.name == "ci-a" && it.tokenHash != result.token && it.status == "ACTIVE" }) }
    }

    @Test
    fun `verify matches only active unexpired token`() {
        val raw = "cop-ci-a-" + java.util.UUID.randomUUID().toString().replace("-", "")
        val hash = encoder.encode(raw)
        val stored = ApiToken().apply { id = 5L; name = "ci-a"; tokenHash = hash; status = "ACTIVE"; expiresAt = null }
        every { repository.findByNameAndStatus("ci-a", "ACTIVE") } returns listOf(stored)

        val ok = service.verify(raw)
        assertNotNull(ok)
        assertEquals(5L, ok.id)
    }

    @Test
    fun `verify rejects wrong token or expired`() {
        val raw = "cop-ci-b-" + java.util.UUID.randomUUID().toString().replace("-", "")
        val hash = encoder.encode(raw)
        val expired = ApiToken().apply { id = 6L; name = "ci-b"; tokenHash = hash; status = "ACTIVE"; expiresAt = Instant.now().minusSeconds(10) }
        every { repository.findByNameAndStatus("ci-b", "ACTIVE") } returns listOf(expired)

        assertNull(service.verify(raw))
    }
}
