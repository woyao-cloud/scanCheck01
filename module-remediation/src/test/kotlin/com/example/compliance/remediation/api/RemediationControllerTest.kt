package com.example.compliance.remediation.api

import com.example.compliance.remediation.application.FindingRemediationView
import com.example.compliance.remediation.application.RemediationService
import com.example.compliance.remediation.application.RemediationTaskView
import com.example.compliance.result.application.FindingView
import com.example.compliance.result.domain.FindingStatus
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/** M7：remediation 控制器切片（finding 中心端点）。 */
@WebMvcTest(RemediationController::class)
@AutoConfigureMockMvc(addFilters = false)   // Security 过滤链关闭：未认证请求直接进控制器
class RemediationControllerTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var service: RemediationService

    /** 控制器依赖经 MockK mock 注入（@MockBean 是 Mockito mock，与 io.mockk.every 不兼容）。 */
    @TestConfiguration
    class RemServiceConfig {
        @Bean
        fun remediationService(): RemediationService = mockk()
    }

    private val view = FindingRemediationView(
        finding = FindingView(
            id = 7L, projectId = 9L, scanTaskId = 1L, ruleCode = "R1", severity = "HIGH",
            status = FindingStatus.ASSIGNED, filePath = "A.java", lineNumber = 1,
            firstSeenAt = Instant.now(), lastSeenAt = Instant.now(), occurrenceCount = 1,
        ),
        task = RemediationTaskView(
            id = 11L, findingId = 7L, projectId = 9L, assigneeUserId = 3L, createdBy = 1L,
            plan = null, dueDate = null, status = FindingStatus.ASSIGNED, createdAt = Instant.now(),
        ),
    )

    @Test
    fun `assign returns assigned finding`() {
        every { service.assign(7L, 1L, 3L, "plan", null) } returns view
        mockMvc.perform(
            post("/api/v1/remediation/findings/7/assign")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"assigneeId":3,"plan":"plan"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.task.assigneeUserId").value(3))
            .andExpect(jsonPath("$.finding.status").value("ASSIGNED"))
    }

    @Test
    fun `list returns findings`() {
        every { service.list(9L, null, null, 0, 20) } returns listOf(view)
        mockMvc.perform(get("/api/v1/remediation/findings").param("projectId", "9"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].finding.id").value(7))
    }

    @Test
    fun `put status returns terminal finding`() {
        every { service.status(7L, FindingStatus.WAIVED, "risk accepted", "DOC", "http://x", 1L) } returns
            view.copy(finding = view.finding.copy(status = FindingStatus.WAIVED))
        mockMvc.perform(
            put("/api/v1/remediation/findings/7/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"WAIVED","reason":"risk accepted","evidenceType":"DOC","evidenceRef":"http://x"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.finding.status").value("WAIVED"))
    }
}
