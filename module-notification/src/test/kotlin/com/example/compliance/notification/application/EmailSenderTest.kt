package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import java.io.InputStream
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmailSenderTest {
    private val provider = mockk<ObjectProvider<JavaMailSender>>()
    private val repository = mockk<NotificationRepository>()
    private val retryBackoff = NotificationRetryBackoff(maxAttempts = 5)
    private val sender = EmailSender(provider, repository, retryBackoff)

    private fun row() = Notification().apply {
        id = 1L; channel = Channel.EMAIL.name; recipient = "a@x.com"; type = "SCAN_COMPLETED"
        title = "scan completed"; content = "body"; status = "PENDING"
    }

    @Test
    fun `send builds mime and marks sent clearing retry fields`() {
        val stub = StubJavaMailSender()
        every { provider.getIfAvailable() } returns stub
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        row.errorMessage = "prev"; row.nextRetryAt = java.time.Instant.now()   // 失败遗留 → 成功应清除
        sender.send(row)
        assertEquals("SENT", row.status)
        assertNotNull(row.sentAt)
        assertNull(row.errorMessage)
        assertNull(row.nextRetryAt)
        assertEquals(1, stub.sent.size)
        assertEquals("a@x.com", stub.sent.single().getRecipients(jakarta.mail.Message.RecipientType.TO)?.first()?.toString())
        assertEquals("scan completed", stub.sent.single().subject)
        verify { repository.save(row) }
    }

    @Test
    fun `send failure marks failed with retry error and backoff gate`() {
        val stub = StubJavaMailSender()
        stub.failWith = RuntimeException("smtp down")
        every { provider.getIfAvailable() } returns stub
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        sender.send(row)
        assertEquals("FAILED", row.status)
        assertEquals(1, row.retryCount)
        assertEquals("smtp down", row.errorMessage)
        assertNotNull(row.nextRetryAt)   // 退避门已排程（R-M18-6）
    }

    @Test
    fun `no sender available marks failed`() {
        every { provider.getIfAvailable() } returns null
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        sender.send(row)
        assertEquals("FAILED", row.status)
        assertEquals("mail sender not configured", row.errorMessage)
    }

    @Test
    fun `isAvailable reflects sender presence`() {
        every { provider.getIfAvailable() } returns StubJavaMailSender()
        assertTrue(sender.isAvailable())
        every { provider.getIfAvailable() } returns null
        assertTrue(!sender.isAvailable())
    }

    /** JavaMailSender 测试替身：捕获 MimeMessage（spec R-M17-D5，不引入 Greenmail）。 */
    class StubJavaMailSender : JavaMailSender {
        val sent = mutableListOf<MimeMessage>()
        var failWith: RuntimeException? = null
        override fun createMimeMessage(): MimeMessage = MimeMessage(jakarta.mail.Session.getInstance(Properties()))
        override fun createMimeMessage(contentStream: InputStream): MimeMessage = MimeMessage(jakarta.mail.Session.getInstance(Properties()), contentStream)
        override fun send(mimeMessage: MimeMessage) { failWith?.let { throw it }; sent += mimeMessage }
        override fun send(vararg mimeMessages: MimeMessage) { mimeMessages.forEach { send(it) } }
        override fun send(vararg simpleMessages: SimpleMailMessage) { }
    }
}
