package com.example.compliance.notification.api

import com.example.compliance.notification.application.NotificationTemplateService
import com.example.compliance.notification.domain.NotificationTemplateVersion
import com.example.compliance.notification.domain.TemplateStatus
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

/** M18 通知模板端点切片（Security 过滤链关闭；RBAC 正负例在集成测试走完整链 —— 镜像 M12 report 先例，PL-M18-1）。 */
@WebMvcTest(NotificationTemplateController::class)
@AutoConfigureMockMvc(addFilters = false)
class NotificationTemplateControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var service: NotificationTemplateService

    @TestConfiguration
    class TplServiceConfig {
        @Bean
        fun notificationTemplateService(): NotificationTemplateService = mockk()
    }

    private fun version() = NotificationTemplateVersion().apply {
        id = 5L; templateId = 1L; versionNo = 1; status = TemplateStatus.DRAFT
        content = """{"title":"scan completed","body":"body"}"""
    }

    @Test
    fun `draft returns version view`() {
        every { service.draft("SCAN_COMPLETED", "scan", any()) } returns version()
        mockMvc.perform(
            post("/api/v1/notification-templates/SCAN_COMPLETED/draft")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"scan","content":{"title":"scan completed","body":"body"}}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.versionNo").value(1))
            .andExpect(jsonPath("$.data.status").value("DRAFT"))
    }

    @Test
    fun `publish returns published version`() {
        val published = version().apply { status = TemplateStatus.PUBLISHED }
        every { service.publish("SCAN_COMPLETED") } returns published
        mockMvc.perform(post("/api/v1/notification-templates/SCAN_COMPLETED/publish"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
    }

    @Test
    fun `versions lists versions`() {
        every { service.versions("SCAN_COMPLETED") } returns listOf(version())
        mockMvc.perform(get("/api/v1/notification-templates/SCAN_COMPLETED/versions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].versionNo").value(1))
    }

    @Test
    fun `disable returns disabled version`() {
        val disabled = version().apply { status = TemplateStatus.DISABLED }
        every { service.disable("SCAN_COMPLETED") } returns disabled
        mockMvc.perform(post("/api/v1/notification-templates/SCAN_COMPLETED/disable"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("DISABLED"))
    }
}
