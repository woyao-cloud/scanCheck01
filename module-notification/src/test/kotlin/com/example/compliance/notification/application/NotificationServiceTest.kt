package com.example.compliance.notification.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.example.compliance.user.domain.User
import com.example.compliance.user.infrastructure.UserRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NotificationServiceTest {
    private val repository = mockk<NotificationRepository>()
    private val userRepository = mockk<UserRepository>()
    private val emailSender = mockk<EmailSender>()
    private val webhookSender = mockk<WebhookSender>()
    private val renderer = mockk<TemplateRenderer>()
    private val service = NotificationService(repository, userRepository, emailSender, webhookSender, renderer)

    private fun captured(): MutableList<Notification> {
        val rows = mutableListOf<Notification>()
        every { repository.save(any<Notification>()) } answers { firstArg<Notification>().also { it.id = 1L; rows += it } }
        return rows
    }

    /** 渲染桩：title/body 固定值（渲染正确性由 TemplateRendererTest 专测，此处只验证接线与 fan-out）。 */
    private fun stubRender() {
        every { renderer.render(any(), any()) } returns ("t" to "b")
    }

    @Test
    fun `notify dedups recipients and creates one IN_APP row per recipient`() {
        val rows = captured()
        stubRender()
        every { emailSender.isAvailable() } returns false
        every { webhookSender.isConfigured() } returns false
        service.notify("SCAN_COMPLETED", mapOf("scanTaskId" to 1L), listOf(1L, 1L, 2L))
        val inApp = rows.filter { it.channel == Channel.IN_APP.name }
        assertEquals(2, inApp.size)
        assertTrue(inApp.all { it.status == "SENT" && it.type == "SCAN_COMPLETED" && it.sentAt != null })
        assertEquals(listOf("1", "2"), inApp.map { it.recipient })
        verify(exactly = 1) { renderer.render("SCAN_COMPLETED", mapOf("scanTaskId" to 1L)) }   // 每事件渲染一次
    }

    @Test
    fun `notify with empty recipients persists nothing`() {
        service.notify("SCAN_COMPLETED", emptyMap(), emptyList())
        verify(exactly = 0) { repository.save(any<Notification>()) }
    }

    @Test
    fun `email rows only for users with email when sender available`() {
        val rows = captured()
        stubRender()
        every { userRepository.findById(1L) } returns Optional.of(User().apply { id = 1L; email = "a@x.com" })
        every { userRepository.findById(2L) } returns Optional.of(User().apply { id = 2L; email = null })
        every { emailSender.isAvailable() } returns true
        every { emailSender.send(any()) } just Runs
        every { webhookSender.isConfigured() } returns false
        service.notify("REMEDIATION_ASSIGNED", mapOf("findingId" to 3L), listOf(1L, 2L))
        val emails = rows.filter { it.channel == Channel.EMAIL.name }
        assertEquals(1, emails.size)
        assertEquals("a@x.com", emails.single().recipient)
        assertEquals("PENDING", emails.single().status)
        verify(exactly = 1) { emailSender.send(emails.single()) }
    }

    @Test
    fun `no email rows when sender unavailable`() {
        val rows = captured()
        stubRender()
        every { emailSender.isAvailable() } returns false
        every { webhookSender.isConfigured() } returns false
        service.notify("SCAN_COMPLETED", emptyMap(), listOf(1L))
        assertTrue(rows.none { it.channel == Channel.EMAIL.name })
        verify(exactly = 0) { userRepository.findById(any()) }
    }

    @Test
    fun `webhook row only when configured with deduped recipient ids on the row`() {
        val rows = captured()
        stubRender()
        every { emailSender.isAvailable() } returns false
        every { webhookSender.isConfigured() } returns true
        every { webhookSender.send(any()) } just Runs
        service.notify("REPORT_SNAPSHOT_GENERATED", mapOf("snapshotId" to 5L), listOf(1L, 1L, 2L))
        val webhook = rows.filter { it.channel == Channel.WEBHOOK.name }
        assertEquals(1, webhook.size)
        assertEquals("webhook", webhook.single().recipient)
        assertEquals("1,2", webhook.single().recipientIds)   // R-M18-5：去重收件人落行
        verify { webhookSender.send(webhook.single()) }      // 新签名 send(row)
    }

    @Test
    fun `listMy negative page throws business exception`() {
        // M5（spec §6）：listMy 分页守卫直接单测（切片只证明 400 翻译）
        val e = assertFailsWith<BusinessException> { service.listMy(1L, -1, 20, false) }
        assertEquals(400, e.code)
    }
}
