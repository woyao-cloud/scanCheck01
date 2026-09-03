package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** M9：通知落库（真实渠道为后续扩展点，M9 仅桩）。 */
class NotificationServiceTest {

    private val repository = mockk<NotificationRepository>()
    private val service = NotificationService(repository)

    @Test
    fun `send persists notification with pending then sent status`() {
        every { repository.save(any<Notification>()) } answers { (firstArg<Notification>()).also { it.id = 3L } }

        val n = service.send("EMAIL", "a@b.c", "SCAN_COMPLETED", "扫描完成", "detail")

        assertEquals(3L, n.id)
        verify(exactly = 2) { repository.save(any<Notification>()) }   // PENDING 落库 + SENT 更新
    }
}
