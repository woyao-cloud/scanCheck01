package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant

/** Webhook 渠道适配器：标准化 JSON 负载 + 状态流转。url 未配置 → isConfigured()=false，不建 WEBHOOK 行。
 *  send 绝不抛出 —— client 异常或 false 一律置 FAILED，保护 notify 的 REQUIRES_NEW 事务。 */
@Service
class WebhookSender(
    @Value("\${compliance.notification.webhook-url:}") private val webhookUrl: String,
    private val client: WebhookClient,
    private val repository: NotificationRepository,
) {
    private val objectMapper = ObjectMapper()

    fun isConfigured(): Boolean = webhookUrl.isNotBlank()

    fun send(row: Notification, recipientIds: List<Long>, occurredAt: Instant) {
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "type" to row.type,
                "title" to row.title,
                "body" to row.content,
                "recipientIds" to recipientIds,
                "occurredAt" to occurredAt.toString(),
            )
        )
        val ok = try {
            client.post(webhookUrl, payload)
        } catch (e: Exception) {
            false
        }
        if (ok) {
            row.status = "SENT"
            row.sentAt = Instant.now()
        } else {
            row.status = "FAILED"
            row.retryCount++
            row.errorMessage = "webhook post failed"
        }
        repository.save(row)
    }
}
