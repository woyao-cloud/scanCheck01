package com.example.compliance.notification.application

import com.example.compliance.common.event.FindingRegressionEvent
import com.example.compliance.common.event.RemediationWaiverEvent
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/** M10 I4 best-effort 单测：发送器抛异常不向上传播（runCatching 兜底，仅日志）。 */
class NotificationEventListenerTest {
    @Test
    fun `sender failure does not propagate`() {
        val sender = mockk<NotificationSender>()
        every { sender.send(any(), any(), any(), any()) } throws RuntimeException("boom")
        val listener = NotificationEventListener(sender)
        listener.onWaiver(RemediationWaiverEvent(1L, 2L, 3L, "r"))   // 不抛 —— runCatching 兜底
        listener.onRegression(FindingRegressionEvent(1L, 2L, listOf(3L)))
    }
}
