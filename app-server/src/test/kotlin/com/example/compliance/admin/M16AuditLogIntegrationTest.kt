package com.example.compliance.admin

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.common.audit.AuditLog
import com.example.compliance.common.audit.AuditLogRepository
import com.example.compliance.common.audit.AuditService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/** M16 端到端：审计日志查询（spec R-M16-D5/D6/D7）——多可选 AND 过滤、时间窗、分页/钳制/负页 400、
 *  RBAC（ADMIN/AUDITOR 200、COMPLIANCE_MANAGER 403、未认证 401）。module=M16_AUDIT_* 每测试独有 → 确定性隔离。 */
@AutoConfigureMockMvc
class M16AuditLogIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var auditService: AuditService
    @Autowired lateinit var auditLogRepository: AuditLogRepository

    @Test
    @WithMockUser(username = "m16-auditor", roles = ["AUDITOR"])
    fun `auditor filters by module action user and resource`() {
        val module = "M16_AUDIT_F"
        auditService.record(action = "CREATE", module = module, userId = 7L, resourceType = "Project", resourceId = 11L)
        auditService.record(action = "UPDATE", module = module, userId = 8L, resourceType = "Rule", resourceId = 22L)
        auditService.record(action = "DELETE", module = module, userId = 9L, resourceType = "Project", resourceId = 33L)

        mockMvc.perform(get("/api/v1/audit-logs").param("module", module))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(3))
            .andExpect(jsonPath("$.data.items[0].action").value("DELETE")) // id DESC：最后插入的 DELETE 最先
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("action", "UPDATE"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].action").value("UPDATE"))
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("userId", "8"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1))
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("resourceType", "Project"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(2))
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("resourceType", "Project").param("resourceId", "11"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1))
    }

    @Test
    @WithMockUser(username = "m16-auditor", roles = ["AUDITOR"])
    fun `auditor time window pagination and clamps`() {
        val module = "M16_AUDIT_T"
        val now = Instant.now()
        auditLogRepository.save(audit(module, "OLD", now.minusSeconds(5 * 86400)))
        auditLogRepository.save(audit(module, "RECENT", now.minusSeconds(86400)))
        auditLogRepository.save(audit(module, "NOW", now))

        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("from", now.minusSeconds(2 * 86400).toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(2))
        // to 单侧：occurredAt <= now-1d → OLD + RECENT（边界含等）
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("to", now.minusSeconds(86400).toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(2))
        // from+to 双侧窗：now-2d .. now-1d → 仅 RECENT
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module)
                .param("from", now.minusSeconds(2 * 86400).toString())
                .param("to", now.minusSeconds(86400).toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].action").value("RECENT"))

        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("page", "0").param("size", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.total").value(3))
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("page", "1").param("size", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items.length()").value(1))

        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("size", "500"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.size").value(100))
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).param("page", "-1"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
    }

    @Test
    fun `rbac admin allowed cm forbidden unauthenticated 401`() {
        val module = "M16_AUDIT_R"
        auditService.record(action = "CREATE", module = module)
        val admin = SecurityMockMvcRequestPostProcessors.user("m16-admin").roles("ADMIN")
        val cm = SecurityMockMvcRequestPostProcessors.user("m16-cm").roles("COMPLIANCE_MANAGER")
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).with(admin))
            .andExpect(status().isOk)
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module).with(cm))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/api/v1/audit-logs").param("module", module))
            .andExpect(status().isUnauthorized)
    }

    private fun audit(module: String, action: String, occurredAt: Instant) = AuditLog().apply {
        this.module = module
        this.action = action
        userId = 1L
        this.detail = """{"marker":"m16-audit"}"""
        this.occurredAt = occurredAt
    }
}
