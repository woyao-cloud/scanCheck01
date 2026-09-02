package com.example.compliance.common.audit

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class AuditService(private val repository: AuditLogRepository) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        action: String,
        module: String,
        userId: Long? = null,
        resourceType: String? = null,
        resourceId: Long? = null,
        detail: String? = null,
        ip: String? = null,
    ) {
        repository.save(
            AuditLog().apply {
                this.action = action
                this.module = module
                this.userId = userId
                this.resourceType = resourceType
                this.resourceId = resourceId
                this.detail = detail
                this.ip = ip
                this.occurredAt = Instant.now()
            }
        )
    }
}
