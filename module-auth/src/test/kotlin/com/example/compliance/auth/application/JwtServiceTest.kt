package com.example.compliance.auth.application

import io.jsonwebtoken.ExpiredJwtException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JwtServiceTest {
    private val secret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val jwtService = JwtService(secret, 120)

    @Test
    fun `issue and parse round-trip carries subject and roles`() {
        val token = jwtService.issue(1L, "alice", listOf("ROLE_ADMIN"))
        val claims = jwtService.parse(token).payload
        assertEquals("alice", claims.subject)
        // Dev (Task 1.3): brief used Long::class.java, which in Kotlin is the PRIMITIVE long.class.
        // jjwt 0.12.6 DefaultClaims.castClaimValue only coerces numbers to the BOXED Long.class, so it
        // threw RequiredTypeException("... to desired type 'long'"). javaObjectType = java.lang.Long
        // makes jjwt's Integer->Long coercion work. Round-trip intent unchanged.
        assertEquals(1L, claims.get("uid", Long::class.javaObjectType))
        assertEquals(listOf("ROLE_ADMIN"), claims.get("roles", List::class.java))
    }

    @Test
    fun `expired token is rejected`() {
        val expired = JwtService(secret, 0)
        val token = expired.issue(1L, "alice", emptyList())
        // Ruling #23: jjwt's parseSignedClaims THROWS ExpiredJwtException on expired tokens —
        // it never returns claims, so assert the rejection instead of reading .payload.
        assertFailsWith<ExpiredJwtException> { expired.parse(token) }
    }

    @Test
    fun `expiration minutes exposed to controller`() {
        assertEquals(120, jwtService.expirationMinutes())
    }
}
