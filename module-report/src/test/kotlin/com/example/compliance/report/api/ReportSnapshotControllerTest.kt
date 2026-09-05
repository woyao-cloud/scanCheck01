package com.example.compliance.report.api

import com.example.compliance.common.exception.GlobalExceptionHandler
import com.example.compliance.report.application.ReportGenerationService
import com.example.compliance.report.application.export.ExportArtifact
import com.example.compliance.report.application.export.ReportExportService
import com.example.compliance.report.domain.ReportSnapshot
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/** M12：快照生成/查询/导出端点切片。GlobalExceptionHandler 显式 @Import：
 *  @WebMvcTest 组件扫描以 ReportTestConfig 包（com.example.compliance.report）为根，
 *  module-common 的 @ControllerAdvice 不在扫描范围，需显式注册才能验证 BusinessException → 400。 */
@WebMvcTest(ReportSnapshotController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class ReportSnapshotControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var generationService: ReportGenerationService
    @Autowired lateinit var exportService: ReportExportService

    private val objectMapper = ObjectMapper()

    @TestConfiguration
    class GenServiceConfig {
        @Bean
        fun reportGenerationService(): ReportGenerationService = mockk()

        @Bean
        fun reportExportService(): ReportExportService = mockk()
    }

    private fun snapshot() = ReportSnapshot().apply {
        id = 3L; templateId = 1L; templateVersionNo = 2; projectId = 88L
        snapshotType = "SCAN_SUMMARY"
        payload = """{"findingCount":3}"""; generatedAt = Instant.now()
    }

    @Test
    fun `generate returns snapshot view`() {
        every { generationService.generate("SCAN_SUMMARY", null, 77L, 1L) } returns snapshot()
        mockMvc.perform(
            post("/api/v1/reports/SCAN_SUMMARY/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"scanTaskId":77}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.snapshotType").value("SCAN_SUMMARY"))
            .andExpect(jsonPath("$.data.templateVersionNo").value(2))
    }

    @Test
    fun `list returns paged summaries`() {
        val page = org.springframework.data.domain.PageImpl(
            listOf(snapshot()),
            org.springframework.data.domain.PageRequest.of(0, 20), 1L,
        )
        every { generationService.list(null, null, 0, 20) } returns page
        mockMvc.perform(get("/api/v1/reports/snapshots"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(3))
    }

    @Test
    fun `detail and export return content`() {
        every { generationService.detail(3L) } returns snapshot()
        every { generationService.export(3L, "json") } returns objectMapper.readTree("""{"findingCount":3}""")
        mockMvc.perform(get("/api/v1/reports/snapshots/3"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.payload.findingCount").value(3))
        mockMvc.perform(get("/api/v1/reports/snapshots/3/export?format=json"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.findingCount").value(3))
    }

    @Test
    fun `export xlsx returns binary attachment`() {
        every { exportService.exportXlsx(3L, 1L) } returns
            ExportArtifact("report-3-scan_summary.xlsx", byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        mockMvc.perform(get("/api/v1/reports/snapshots/3/export?format=xlsx"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report-3-scan_summary.xlsx\""))
            .andExpect(content().bytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
    }

    @Test
    fun `export pdf returns binary attachment`() {
        every { exportService.exportPdf(3L, 1L) } returns
            ExportArtifact("report-3-scan_summary.pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46))
        mockMvc.perform(get("/api/v1/reports/snapshots/3/export?format=pdf"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report-3-scan_summary.pdf\""))
            .andExpect(content().bytes(byteArrayOf(0x25, 0x50, 0x44, 0x46)))
    }

    @Test
    fun `unsupported export format is 400`() {
        mockMvc.perform(get("/api/v1/reports/snapshots/3/export?format=bad"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
    }
}
