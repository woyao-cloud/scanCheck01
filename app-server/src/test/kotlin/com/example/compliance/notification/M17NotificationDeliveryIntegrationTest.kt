package com.example.compliance.notification

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.common.event.RemediationAssignedEvent
import com.example.compliance.common.event.ScanCompletedEvent
import com.example.compliance.notification.application.WebhookClient
import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.example.compliance.project.domain.Project
import com.example.compliance.project.infrastructure.ProjectRepository
import com.example.compliance.user.domain.User
import com.example.compliance.user.infrastructure.UserRepository
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.TestPropertySource
import java.io.InputStream
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M17 投递 e2e：真实监听器/notify + stub 渠道（spec R-M17-D5，不引入 Greenmail/MockWebServer）。
 *  @TestPropertySource 开启 webhook-url → WEBHOOK 行创建；stub bean @Primary 覆盖 WebhookClient（RestWebhookClient 同型歧义）。
 *  数据前缀：项目 M17DLV1/2、用户 m17dlv-*（跨类全局唯一，共享容器）。 */
@TestPropertySource(properties = ["compliance.notification.webhook-url=http://webhook.test/hook"])
class M17NotificationDeliveryIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var publisher: ApplicationEventPublisher
    @Autowired lateinit var repository: NotificationRepository
    @Autowired lateinit var projectRepository: ProjectRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var mailStub: StubJavaMailSender
    @Autowired lateinit var webhookStub: StubWebhookClient

    @BeforeEach
    fun resetStubs() {
        mailStub.sent.clear(); mailStub.failWith = null
        webhookStub.posts.clear(); webhookStub.failWith = null
    }

    private fun ownerUser(username: String, email: String): Long =
        userRepository.save(User().apply { this.username = username; this.email = email; passwordHash = "x" }).id!!

    @Test
    fun `scan completed notifies owner by email and webhook with statuses`() {
        val owner = ownerUser("m17dlv-owner", "m17dlv-owner@example.com")
        val projectId = projectRepository.save(Project().apply { code = "M17DLV1"; name = "m17"; ownerUserId = owner }).id!!

        publisher.publishEvent(ScanCompletedEvent(901L, projectId, "SUCCESS"))

        // IN_APP：落库即 SENT
        val inApp = repository.findByRecipient(owner.toString()).filter { it.type == "SCAN_COMPLETED" }
        assertTrue(inApp.any { it.channel == Channel.IN_APP.name && it.status == "SENT" })

        // EMAIL：经 StubJavaMailSender 捕获 MimeMessage，行 SENT
        val emailRow = repository.findByRecipient("m17dlv-owner@example.com").filter { it.type == "SCAN_COMPLETED" }
        assertEquals(1, emailRow.size)
        assertEquals("SENT", emailRow.single().status)
        assertEquals(1, mailStub.sent.size)
        assertEquals(
            "m17dlv-owner@example.com",
            mailStub.sent.single().getRecipients(jakarta.mail.Message.RecipientType.TO)?.first()?.toString(),
        )
        assertEquals("scan completed", mailStub.sent.single().subject)

        // WEBHOOK：经 StubWebhookClient 捕获 POST，行 SENT
        val webhookRow = repository.findByRecipient("webhook").filter { it.type == "SCAN_COMPLETED" }
        assertEquals(1, webhookRow.size)
        assertEquals("SENT", webhookRow.single().status)
        assertEquals(1, webhookStub.posts.size)
        assertEquals("http://webhook.test/hook", webhookStub.posts.single().url)
        assertTrue(webhookStub.posts.single().body.contains("\"type\":\"SCAN_COMPLETED\""))
        assertTrue(webhookStub.posts.single().body.contains("\"recipientIds\":"))
    }

    @Test
    fun `delivery failure marks failed but publisher flow unaffected`() {
        mailStub.failWith = RuntimeException("smtp down")
        webhookStub.failWith = RuntimeException("http down")
        val assignee = ownerUser("m17dlv-owner2", "m17dlv-owner2@example.com")

        publisher.publishEvent(RemediationAssignedEvent(3L, 99L, assignee))   // 不抛 —— best-effort 实证

        // IN_APP 行不受渠道失败影响
        val inApp = repository.findByRecipient(assignee.toString()).filter { it.type == "REMEDIATION_ASSIGNED" }
        assertTrue(inApp.any { it.status == "SENT" })

        // EMAIL/WEBHOOK 行 FAILED + retry_count + error_message
        val emailRow = repository.findByRecipient("m17dlv-owner2@example.com").filter { it.type == "REMEDIATION_ASSIGNED" }.single()
        assertEquals("FAILED", emailRow.status)
        assertEquals(1, emailRow.retryCount)
        assertTrue(!emailRow.errorMessage.isNullOrBlank())

        val webhookRow = repository.findByRecipient("webhook").filter { it.type == "REMEDIATION_ASSIGNED" }.single()
        assertEquals("FAILED", webhookRow.status)
    }

    @TestConfiguration
    class DeliveryStubConfig {
        @Bean
        @Primary
        fun mailSender(): JavaMailSender = StubJavaMailSender()

        @Bean
        @Primary
        fun webhookClient(): WebhookClient = StubWebhookClient()
    }

    class StubJavaMailSender : JavaMailSender {
        val sent = mutableListOf<MimeMessage>()
        var failWith: RuntimeException? = null
        override fun createMimeMessage(): MimeMessage = MimeMessage(jakarta.mail.Session.getInstance(Properties()))
        override fun createMimeMessage(contentStream: InputStream): MimeMessage =
            MimeMessage(jakarta.mail.Session.getInstance(Properties()), contentStream)
        override fun send(mimeMessage: MimeMessage) { failWith?.let { throw it }; sent += mimeMessage }
        override fun send(vararg mimeMessages: MimeMessage) { mimeMessages.forEach { send(it) } }
        override fun send(vararg simpleMessages: SimpleMailMessage) { }
    }

    class StubWebhookClient : WebhookClient {
        val posts = mutableListOf<Post>()
        var failWith: RuntimeException? = null
        data class Post(val url: String, val body: String)
        override fun post(url: String, body: String): Boolean {
            failWith?.let { throw it }
            posts += Post(url, body)
            return true
        }
    }
}
