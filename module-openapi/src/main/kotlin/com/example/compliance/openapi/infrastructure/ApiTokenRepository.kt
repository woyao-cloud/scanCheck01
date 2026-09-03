package com.example.compliance.openapi.infrastructure

import com.example.compliance.openapi.domain.ApiToken
import org.springframework.data.jpa.repository.JpaRepository

interface ApiTokenRepository : JpaRepository<ApiToken, Long> {
    fun findByNameAndStatus(name: String, status: String): List<ApiToken>
    fun findAllByStatus(status: String): List<ApiToken>
    fun existsByName(name: String): Boolean
}
