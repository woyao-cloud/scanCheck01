package com.example.compliance.notification.api

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.common.exception.GlobalExceptionHandler
import com.example.compliance.notification.application.NotificationService
import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/** M17 站内信端点切片（镜像 M16：GlobalExceptionHandler 显式 @Import 才能断言 400 {code:400}）。 */
@WebMvcTest(NotificationController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class NotificationControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var service: NotificationService

    @TestConfiguration
    class NotifServiceConfig {
        @Bean
        fun notificationService(): NotificationService = mockk(relaxUnitFun = true)
    }

    private fun row(id: Long) = Notification().apply {
        this.id = id; channel = Channel.IN_APP.name; recipient = "1"; type = "SCAN_COMPLETED"
        title = "scan completed"; content = "body"; status = "SENT"; createdAt = Instant.now()
    }

    @Test
    fun `list returns paged views for current user`() {
        val page = PageImpl(listOf(row(1L)), PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id")), 1L)
        every { service.listMy(1L, 0, 20, false) } returns page
        mockMvc.perform(get("/api/v1/notifications"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(1))
            .andExpect(jsonPath("$.data.items[0].status").value("SENT"))
    }

    @Test
    fun `list unreadOnly passes flag`() {
        every { service.listMy(1L, 0, 20, true) } returns PageImpl(emptyList(), PageRequest.of(0, 20), 0L)
        mockMvc.perform(get("/api/v1/notifications?unreadOnly=true"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(0))
    }

    @Test
    fun `unread-count returns count`() {
        every { service.unreadCount(1L) } returns 3L
        mockMvc.perform(get("/api/v1/notifications/unread-count"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.count").value(3))
    }

    @Test
    fun `read marks notification read and is idempotent`() {
        mockMvc.perform(post("/api/v1/notifications/7/read"))
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/notifications/7/read"))
            .andExpect(status().isOk)
        verify(exactly = 2) { service.markRead(7L, 1L) }
    }

    @Test
    fun `read-all returns count`() {
        every { service.markReadAll(1L) } returns 2
        mockMvc.perform(post("/api/v1/notifications/read-all"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.count").value(2))
    }

    @Test
    fun `read foreign or missing notification is 404 via global handler`() {
        every { service.markRead(9L, 1L) } throws BusinessException(404, "notification not found: 9")
        mockMvc.perform(post("/api/v1/notifications/9/read"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(404))
    }

    @Test
    fun `page below zero is 400 via global handler`() {
        every { service.listMy(1L, -1, 20, false) } throws BusinessException(400, "page must be non-negative")
        mockMvc.perform(get("/api/v1/notifications?page=-1"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
    }
}
