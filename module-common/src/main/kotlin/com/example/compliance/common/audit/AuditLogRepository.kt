package com.example.compliance.common.audit

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

interface AuditLogRepository : JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog>
