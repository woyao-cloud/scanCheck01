package com.example.compliance.notification

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.common.event.ScanCompletedEvent
import com.example.compliance.notification.application.NotificationRetryJob
import com.example.compliance.notification.application.WebhookClient
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
import java.time.Instant
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M18 投递重试 e2e：首投失败（stub 渠道）→ FAILED+retryCount+next_retry_at → 手动触发 job.retryFailed()
 *  （退避门 next_retry_at 拨回过去，避免等 fixedDelay，spec §9.3）→ 重试成功 SENT。
 *  PL-M18-4：@TestPropertySource 独立上下文；fixed-delay-ms 放大 1h —— @Scheduled 初始 run 在上下文启动时
 *  （无 FAILED 行 → no-op），1h 内不会自动触发干扰，只走手动触发。数据前缀 M18RTY1/2、用户 m18rty-*。 */
@TestPropertySource(properties = [
    "compliance.notification.webhook-url=http://webhook.test/retry",
    "compliance.notification.retry.fixed-delay-ms=3600000",
])
class M18NotificationRetryIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var publisher: ApplicationEventPublisher
    @Autowired lateinit var repository: NotificationRepository
    @Autowired lateinit var retryJob: NotificationRetryJob
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

    private fun project(code: String, ownerId: Long): Long =
        projectRepository.save(Project().apply { this.code = code; name = "m18"; ownerUserId = ownerId }).id!!

    private fun webhookRows(type: String) = repository.findByRecipient("webhook").filter { it.type == type }

    @Test
    fun `failed delivery is retried to sent once backoff elapsed`() {
        webhookStub.failWith = RuntimeException("http down")
        val owner = ownerUser("m18rty-owner1", "m18rty-owner1@example.com")
        val projectId = project("M18RTY1", owner)

        publisher.publishEvent(ScanCompletedEvent(2001L, projectId, "SUCCESS"))

        // 首投失败：WEBHOOK 行 FAILED + retryCount=1 + next_retry_at 已排程（EMAIL 行同样 FAILED 但 next_retry_at 未到）
        val failed = webhookRows("SCAN_COMPLETED").maxByOrNull { it.id!! }!!
        assertEquals("FAILED", failed.status)
        assertEquals(1, failed.retryCount)
        assertTrue(failed.nextRetryAt != null)
        assertTrue(!failed.errorMessage.isNullOrBlank())

        // 退避已到：把 next_retry_at 拨回过去（模拟 60s 流逝）；渠道恢复
        failed.nextRetryAt = Instant.now().minusSeconds(10)
        repository.save(failed)
        webhookStub.failWith = null

        retryJob.retryFailed()

        val retried = webhookRows("SCAN_COMPLETED").maxByOrNull { it.id!! }!!
        assertEquals("SENT", retried.status)
        assertNull(retried.errorMessage)
        assertNull(retried.nextRetryAt)
        assertEquals(1, retried.retryCount)   // 成功不清 retryCount（M17 语义）
        assertEquals(1, webhookStub.posts.size)
        // WebhookSender 序列化 recipientIds 为数值 JSON 数组（recipientIds 落行存 userIds 逗号串 → Long 列表）
        assertTrue(webhookStub.posts.single().body.contains("\"recipientIds\":[$owner]"))
    }

    @Test
    fun `row at max attempts is not re-candidated`() {
        webhookStub.failWith = RuntimeException("http down")
        val owner = ownerUser("m18rty-owner2", "m18rty-owner2@example.com")
        val projectId = project("M18RTY2", owner)

        publisher.publishEvent(ScanCompletedEvent(2002L, projectId, "SUCCESS"))

        val webhookRow = webhookRows("SCAN_COMPLETED").maxByOrNull { it.id!! }!!
        // 预置到第 4 次失败（maxAttempts 默认 5）且退避已到
        webhookRow.retryCount = 4
        webhookRow.nextRetryAt = Instant.now().minusSeconds(10)
        repository.save(webhookRow)

        retryJob.retryFailed()   // 第 5 次失败 → 达上限

        val capped = webhookRows("SCAN_COMPLETED").maxByOrNull { it.id!! }!!
        assertEquals("FAILED", capped.status)
        assertEquals(5, capped.retryCount)
        assertNull(capped.nextRetryAt)
        assertTrue(capped.errorMessage!!.contains("max attempts reached"))

        val postsAfterCap = webhookStub.posts.size
        retryJob.retryFailed()   // 不再候选（retryCount 5 !< 5）
        assertEquals(postsAfterCap, webhookStub.posts.size)   // 未再投递
        assertEquals("FAILED", webhookRows("SCAN_COMPLETED").maxByOrNull { it.id!! }!!.status)
    }

    @TestConfiguration
    class RetryStubConfig {
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
