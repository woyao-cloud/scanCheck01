package com.example.compliance

import com.example.compliance.common.audit.AuditLogRepository
import com.example.compliance.common.audit.AuditService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals

class SmokeIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    lateinit var auditService: AuditService

    @Autowired
    lateinit var auditLogRepository: AuditLogRepository

    @Test
    fun `flyway migrates and audit persists to real postgres`() {
        auditService.record(action = "SMOKE", module = "test", userId = 1L)
        assertEquals(1L, auditLogRepository.count())
    }
}
