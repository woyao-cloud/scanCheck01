package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookSenderTest {
    private val client = mockk<WebhookClient>()
    private val repository = mockk<NotificationRepository>()
    private val sender = WebhookSender("http://hook.test/x", client, repository)

    private fun row() = Notification().apply {
        id = 1L; channel = Channel.WEBHOOK.name; recipient = "webhook"; type = "SCAN_COMPLETED"
        title = "scan completed"; content = "body"; status = "PENDING"
    }

    @Test
    fun `send posts json and marks sent`() {
        every { client.post("http://hook.test/x", any()) } returns true
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        sender.send(row, listOf(1L, 2L), Instant.parse("2026-09-06T00:00:00Z"))
        assertEquals("SENT", row.status)
        val posted = slot<String>()
        verify { client.post("http://hook.test/x", capture(posted)) }
        assertTrue(posted.captured.contains("\"type\":\"SCAN_COMPLETED\""))
        assertTrue(posted.captured.contains("\"recipientIds\":[1,2]"))
        assertTrue(posted.captured.contains("2026-09-06T00:00:00Z"))
    }

    @Test
    fun `send failure marks failed and increments retry`() {
        every { client.post(any(), any()) } returns false
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        sender.send(row, listOf(1L), Instant.now())
        assertEquals("FAILED", row.status)
        assertEquals(1, row.retryCount)
        assertEquals("webhook post failed", row.errorMessage)
    }

    @Test
    fun `send swallows client exception as failed`() {
        every { client.post(any(), any()) } throws RuntimeException("http down")
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        sender.send(row, listOf(1L), Instant.now())
        assertEquals("FAILED", row.status)
    }

    @Test
    fun `isConfigured reflects url`() {
        assertTrue(sender.isConfigured())
        assertFalse(WebhookSender("", client, repository).isConfigured())
    }
}
