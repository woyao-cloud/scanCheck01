package com.example.compliance.common.audit

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class AuditServiceTest {
    @Test
    fun `record persists an audit log row`() {
        val repo = mockk<AuditLogRepository>(relaxed = true)
        every { repo.save(any()) } answers { firstArg() }
        val service = AuditService(repo)
        service.record(action = "CREATE", module = "project", userId = 1L, resourceType = "Project", resourceId = 9L)
        verify(exactly = 1) { repo.save(any()) }
    }
}
