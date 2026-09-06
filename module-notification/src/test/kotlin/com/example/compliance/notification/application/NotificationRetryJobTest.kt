package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class NotificationRetryJobTest {
    private val repository = mockk<NotificationRepository>()
    private val emailSender = mockk<EmailSender>()
    private val webhookSender = mockk<WebhookSender>()
    private val backoff = NotificationRetryBackoff(maxAttempts = 5)
    private val job = NotificationRetryJob(repository, emailSender, webhookSender, backoff)

    private fun row(channel: String) = Notification().apply {
        id = 1L; this.channel = channel; type = "SCAN_COMPLETED"; status = "FAILED"; retryCount = 1
    }

    @Test
    fun `retryFailed queries candidates and dispatches by channel`() {
        val email = row(Channel.EMAIL.name)
        val webhook = row(Channel.WEBHOOK.name)
        every { repository.findByStatusAndChannelInAndRetryCountLessThanAndNextRetryAtLessThanEqual(any(), any(), any(), any()) } returns listOf(email, webhook)
        every { emailSender.send(email) } just Runs
        every { webhookSender.send(webhook) } just Runs
        job.retryFailed()
        verify(exactly = 1) { emailSender.send(email) }
        verify(exactly = 1) { webhookSender.send(webhook) }
    }

    @Test
    fun `retryFailed uses failed email and webhook channels below max with elapsed gate`() {
        every { repository.findByStatusAndChannelInAndRetryCountLessThanAndNextRetryAtLessThanEqual(any(), any(), any(), any()) } returns emptyList()
        job.retryFailed()
        verify {
            repository.findByStatusAndChannelInAndRetryCountLessThanAndNextRetryAtLessThanEqual(
                "FAILED", listOf(Channel.EMAIL.name, Channel.WEBHOOK.name), 5, any(),
            )
        }
    }

    @Test
    fun `exception in one row does not kill the batch`() {
        val email = row(Channel.EMAIL.name)
        val webhook = row(Channel.WEBHOOK.name)
        every { repository.findByStatusAndChannelInAndRetryCountLessThanAndNextRetryAtLessThanEqual(any(), any(), any(), any()) } returns listOf(email, webhook)
        every { emailSender.send(email) } throws RuntimeException("boom")
        every { webhookSender.send(webhook) } just Runs
        job.retryFailed()   // 不抛 —— runCatching 逐行兜底（R-M18-11）
        verify(exactly = 1) { webhookSender.send(webhook) }
    }
}
