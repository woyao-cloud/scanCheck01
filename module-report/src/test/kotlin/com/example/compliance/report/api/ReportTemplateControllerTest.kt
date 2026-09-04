package com.example.compliance.report.api

import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.report.application.ReportTemplateService
import com.example.compliance.report.domain.ReportTemplateVersion
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** M12：报告模板端点切片（Security 过滤链关闭；RBAC 负例在 Task 12.4 集成测试走完整链）。 */
@WebMvcTest(ReportTemplateController::class)
@AutoConfigureMockMvc(addFilters = false)
class ReportTemplateControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var service: ReportTemplateService

    @TestConfiguration
    class TplServiceConfig {
        @Bean
        fun reportTemplateService(): ReportTemplateService = mockk()
    }

    private fun version() = ReportTemplateVersion().apply {
        id = 5L; templateId = 1L; versionNo = 1; status = VersionStatus.DRAFT
        sections = """{"sections":[{"title":"Summary"}]}"""
    }

    @Test
    fun `draft returns version view`() {
        every { service.draft("SCAN_SUMMARY", "scan report", any()) } returns version()
        mockMvc.perform(
            post("/api/v1/reports/templates/SCAN_SUMMARY/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"scan report","sections":{"sections":[{"title":"Summary"}]}}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.versionNo").value(1))
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
    }

    @Test
    fun `publish returns published version`() {
        val published = version().apply { status = VersionStatus.PUBLISHED }
        every { service.publish("SCAN_SUMMARY") } returns published
        mockMvc.perform(post("/api/v1/reports/templates/SCAN_SUMMARY/publish"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
    }

    @Test
    fun `versions lists versions`() {
        every { service.versions("SCAN_SUMMARY") } returns listOf(version())
        mockMvc.perform(get("/api/v1/reports/templates/SCAN_SUMMARY/versions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].versionNo").value(1))
    }

    @Test
    fun `disable returns disabled version`() {
        val disabled = version().apply { status = VersionStatus.DISABLED }
        every { service.disable("SCAN_SUMMARY") } returns disabled
        mockMvc.perform(post("/api/v1/reports/templates/SCAN_SUMMARY/disable"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("DISABLED"))
    }
}
