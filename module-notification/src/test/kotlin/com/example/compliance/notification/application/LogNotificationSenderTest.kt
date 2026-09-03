package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import io.mockk.every
import io.mockk.Runs
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class LogNotificationSenderTest {
    @Test
    fun `delegates to NotificationService after logging`() {
        val delegate = mockk<NotificationService>()
        every { delegate.send(any(), any(), any(), any()) } just Runs
        val sender = LogNotificationSender(delegate)
        sender.send(Channel.WECHAT, "主题", "正文", listOf(1L))
        verify(exactly = 1) { delegate.send(Channel.WECHAT, "主题", "正文", listOf(1L)) }
    }
}
