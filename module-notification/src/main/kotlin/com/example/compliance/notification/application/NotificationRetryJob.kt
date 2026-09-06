package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.infrastructure.NotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/** M18 §5.5 投递重试 Job：FAILED 且未达最大次数且退避到期的 EMAIL/WEBHOOK 行重投。
 *  无外层事务（R-M18-11）：sender 各自落库（auto-commit），单行失败不影响整批；
 *  逐行 runCatching 为双保险（sender 本就不抛）。@Scheduled 默认单线程 + next_retry_at 门防并发。 */
@Component
class NotificationRetryJob(
    private val repository: NotificationRepository,
    private val emailSender: EmailSender,
    private val webhookSender: WebhookSender,
    private val retryBackoff: NotificationRetryBackoff,
) {
    private val log = LoggerFactory.getLogger(NotificationRetryJob::class.java)

    @Scheduled(fixedDelayString = "\${compliance.notification.retry.fixed-delay-ms:60000}")
    fun retryFailed() {
        val candidates = repository.findByStatusAndChannelInAndRetryCountLessThanAndNextRetryAtLessThanEqual(
            "FAILED", listOf(Channel.EMAIL.name, Channel.WEBHOOK.name), retryBackoff.maxAttempts, Instant.now(),
        )
        candidates.forEach { row ->
            runCatching {                                  // R-M18-11 双保险：单行异常不杀批
                when (row.channel) {
                    Channel.EMAIL.name -> emailSender.send(row)
                    Channel.WEBHOOK.name -> webhookSender.send(row)
                }
            }.onFailure { log.warn("retry notification {} failed unexpectedly", row.id, it) }
        }
    }
}
