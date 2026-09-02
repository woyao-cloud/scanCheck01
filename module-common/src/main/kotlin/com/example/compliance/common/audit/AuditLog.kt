package com.example.compliance.common.audit

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "audit_log", indexes = [Index(name = "idx_audit_resource", columnList = "resource_type, resource_id")])
class AuditLog : BaseEntity() {
    @Column(name = "user_id")
    var userId: Long? = null

    @Column(name = "action", nullable = false, length = 64)
    lateinit var action: String

    @Column(name = "module", nullable = false, length = 64)
    lateinit var module: String

    @Column(name = "resource_type", length = 64)
    var resourceType: String? = null

    @Column(name = "resource_id")
    var resourceId: Long? = null

    @Column(name = "detail", columnDefinition = "jsonb")
    var detail: String? = null

    @Column(name = "ip", length = 64)
    var ip: String? = null

    @Column(name = "occurred_at", nullable = false)
    lateinit var occurredAt: Instant
}
