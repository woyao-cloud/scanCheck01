package com.example.compliance.admin.api

import com.example.compliance.common.audit.AuditLog
import com.example.compliance.common.audit.AuditLogFilter
import com.example.compliance.common.audit.AuditQueryService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/** M16 (R-M16-D7)：审计查询端点切片——过滤参数透传 + PageView 封装。 */
@WebMvcTest(AuditLogController::class)
@AutoConfigureMockMvc(addFilters = false)
class AuditLogControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var query: AuditQueryService

    @TestConfiguration
    class QueryConfig {
        @Bean
        fun auditQueryService(): AuditQueryService = mockk()
    }

    private fun auditLog(id: Long) = AuditLog().apply {
        this.id = id
        action = "CREATE"; module = "project"; userId = 1L
        resourceType = "Project"; resourceId = 9L
        detail = """{"k":"v"}"""; occurredAt = Instant.parse("2026-09-01T00:00:00Z")
    }

    @Test
    fun `list returns paged audit views`() {
        val page = PageImpl(
            listOf(auditLog(1L)),
            PageRequest.of(0, 20, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")),
            1L,
        )
        every { query.search(any(), 0, 20) } returns page
        mockMvc.perform(get("/api/v1/audit-logs").param("module", "project").param("action", "CREATE"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(1))
            .andExpect(jsonPath("$.data.items[0].module").value("project"))
            .andExpect(jsonPath("$.data.items[0].detail").value("""{"k":"v"}"""))
            .andExpect(jsonPath("$.data.items[0].occurredAt").value("2026-09-01T00:00:00Z"))
    }
}
