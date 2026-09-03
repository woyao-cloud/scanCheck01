package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NotificationServiceTest {
    private val repository = mockk<NotificationRepository>()
    private val service = NotificationService(repository)

    @Test
    fun `send persists one row per recipient pending then sent`() {
        every { repository.save(any<Notification>()) } answers { (firstArg<Notification>()).also { it.id = 3L } }
        service.send(Channel.IN_APP, "扫描完成", "detail", recipients = listOf(1L, 2L))
        verify(exactly = 4) { repository.save(any<Notification>()) }   // 2 接收人 × (PENDING + SENT)
    }

    @Test
    fun `persist writes channel as enum name and type EVENT`() {
        val slot = mutableListOf<Notification>()
        // 与既有 M9 测试同款 answer：set id 模拟保存（BaseEntity.id 默认 null）
        every { repository.save(any<Notification>()) } answers { firstArg<Notification>().also { it.id = 3L; slot += it } }
        service.persist(Channel.IN_APP, "1", "标题", "正文")
        assertEquals("IN_APP", slot.first().channel)
        assertEquals("EVENT", slot.first().type)
        assertEquals("SENT", slot.last().status)
        assertEquals(3L, slot.last().id)
    }
}
