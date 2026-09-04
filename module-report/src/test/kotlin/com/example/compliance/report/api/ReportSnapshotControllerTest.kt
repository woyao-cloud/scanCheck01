package com.example.compliance.report.api

import com.example.compliance.report.application.ReportGenerationService
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
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/** M12：快照生成/查询/导出端点切片。 */
@WebMvcTest(ReportSnapshotController::class)
@AutoConfigureMockMvc(addFilters = false)
class ReportSnapshotControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var generationService: ReportGenerationService

    private val objectMapper = ObjectMapper()

    @TestConfiguration
    class GenServiceConfig {
        @Bean
        fun reportGenerationService(): ReportGenerationService = mockk()
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
}
