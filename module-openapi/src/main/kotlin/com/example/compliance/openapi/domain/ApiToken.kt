package com.example.compliance.openapi.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

/** CI 触发 API Token：token_hash 存 BCrypt；明文仅创建时返回一次。 */
@Entity
@Table(name = "api_token")
class ApiToken : BaseEntity() {
    @Column(name = "name", nullable = false, unique = true, length = 64)
    var name: String = ""
    @Column(name = "token_hash", nullable = false, length = 128)
    var tokenHash: String = ""
    @Column(name = "status", nullable = false, length = 16)
    var status: String = "ACTIVE"
    @Column(name = "expires_at")
    var expiresAt: Instant? = null
    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null
    @Column(name = "created_by")
    var createdBy: Long? = null
}
