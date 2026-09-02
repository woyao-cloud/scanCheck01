package com.example.compliance.auth.application

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jws
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Date
import javax.crypto.SecretKey

@Service
class JwtService(
    @Value("\${app.jwt.secret}") private val secret: String,
    @Value("\${app.jwt.expiration-minutes:120}") private val expirationMinutesValue: Long,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray())

    fun issue(userId: Long, username: String, roles: List<String>): String =
        Jwts.builder()
            .subject(username)
            .claim("uid", userId)
            .claim("roles", roles)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + expirationMinutesValue * 60_000))
            .signWith(key)
            .compact()

    fun parse(token: String): Jws<Claims> =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token)

    fun expirationMinutes(): Long = expirationMinutesValue
}
