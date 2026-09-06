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
import kotlin.test.assertTrue

class EmailSenderTest {
    private val provider = mockk<ObjectProvider<JavaMailSender>>()
    private val repository = mockk<NotificationRepository>()
    private val sender = EmailSender(provider, repository)

    private fun row() = Notification().apply {
        id = 1L; channel = Channel.EMAIL.name; recipient = "a@x.com"; type = "SCAN_COMPLETED"
        title = "scan completed"; content = "body"; status = "PENDING"
    }

    @Test
    fun `send builds mime and marks sent`() {
        val stub = StubJavaMailSender()
        every { provider.getIfAvailable() } returns stub
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        sender.send(row)
        assertEquals("SENT", row.status)
        assertNotNull(row.sentAt)
        assertEquals(1, stub.sent.size)
        assertEquals("a@x.com", stub.sent.single().getRecipients(jakarta.mail.Message.RecipientType.TO)?.first()?.toString())
        assertEquals("scan completed", stub.sent.single().subject)
        verify { repository.save(row) }
    }

    @Test
    fun `send failure marks failed with retry and error`() {
        val stub = StubJavaMailSender()
        stub.failWith = RuntimeException("smtp down")
        every { provider.getIfAvailable() } returns stub
        every { repository.save(any()) } answers { firstArg() }
        val row = row()
        sender.send(row)
        assertEquals("FAILED", row.status)
        assertEquals(1, row.retryCount)
        assertTrue(!row.errorMessage.isNullOrBlank())
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
