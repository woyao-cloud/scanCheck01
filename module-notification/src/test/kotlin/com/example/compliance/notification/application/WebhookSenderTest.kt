package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebhookSenderTest {
    private val client = mockk<WebhookClient>()
    private val repository = mockk<NotificationRepository>()
    private val retryBackoff = NotificationRetryBackoff(maxAttempts = 5)
    private val sender = WebhookSender("http://hook.test/x", client, repository, retryBackoff, ObjectMapper())

    private fun row() = Notification().apply {
        id = 1L; channel = Channel.WEBHOOK.name; recipient = "webhook"; type = "SCAN_COMPLETED"
        title = "scan completed"; content = "body"; status = "PENDING"
        recipientIds = "1,2"; occurredAt = Instant.parse("2026-09-06T00:00:00Z")
    }

    @Test
    fun `send posts json built from row and marks sent clearing retry fields`() {
        every { client.post("http://hook.test/x", any()) } returns true
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        row.errorMessage = "prev"; row.nextRetryAt = Instant.now()   // 失败遗留 → 成功应清除
        sender.send(row)
        assertEquals("SENT", row.status)
        assertNull(row.errorMessage)
        assertNull(row.nextRetryAt)
        val posted = slot<String>()
        verify { client.post("http://hook.test/x", capture(posted)) }
        assertTrue(posted.captured.contains("\"type\":\"SCAN_COMPLETED\""))
        assertTrue(posted.captured.contains("\"recipientIds\":[1,2]"))   // 从行读 recipient_ids 重建
        assertTrue(posted.captured.contains("2026-09-06T00:00:00Z"))     // 从行读 occurred_at 重建
    }

    @Test
    fun `send failure marks failed and increments retry`() {
        every { client.post(any(), any()) } returns false
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        sender.send(row)
        assertEquals("FAILED", row.status)
        assertEquals(1, row.retryCount)
        assertEquals("webhook post failed", row.errorMessage)
        assertTrue(row.nextRetryAt != null)
    }

    @Test
    fun `send swallows client exception as failed`() {
        every { client.post(any(), any()) } throws RuntimeException("http down")
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        sender.send(row)
        assertEquals("FAILED", row.status)
        assertEquals("http down", row.errorMessage)
    }

    @Test
    fun `serialization failure marks failed via onFailure`() {
        // M1：序列化在 try 内 —— 注入可抛异常的 ObjectMapper，证明异常不外泄、走 onFailure
        val mapper = mockk<ObjectMapper>()
        every { mapper.writeValueAsString(any()) } throws RuntimeException("serialize boom")
        val throwingSender = WebhookSender("http://hook.test/x", client, repository, retryBackoff, mapper)
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        throwingSender.send(row)
        assertEquals("FAILED", row.status)
        assertEquals("serialize boom", row.errorMessage)
        assertEquals(1, row.retryCount)
    }

    @Test
    fun `isConfigured reflects url`() {
        assertTrue(sender.isConfigured())
        assertFalse(WebhookSender("", client, repository, retryBackoff, ObjectMapper()).isConfigured())
    }
}
