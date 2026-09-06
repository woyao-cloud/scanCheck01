package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant

/** Webhook 渠道适配器：标准化 JSON 负载 + 状态流转。url 未配置 → isConfigured()=false，不建 WEBHOOK 行。
 *  send 绝不抛出 —— client 异常或 false 一律置 FAILED（经 retryBackoff.onFailure），保护 notify 的 REQUIRES_NEW 事务。
 *  M18 §5.4 (R-M18-5)：send(row) 从行读 recipientIds/occurredAt 重建 payload（落库后重试同路径）；
 *  M1：objectMapper.writeValueAsString 移入 try/catch 内。 */
@Service
class WebhookSender(
    @Value("\${compliance.notification.webhook-url:}") private val webhookUrl: String,
    private val client: WebhookClient,
    private val repository: NotificationRepository,
    private val retryBackoff: NotificationRetryBackoff,
    private val objectMapper: ObjectMapper,
) {
    fun isConfigured(): Boolean = webhookUrl.isNotBlank()

    fun send(row: Notification) {
        val ok: Boolean
        try {
            val payload = objectMapper.writeValueAsString(
                mapOf(
                    "type" to row.type,
                    "title" to row.title,
                    "body" to row.content,
                    "recipientIds" to (row.recipientIds?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()),
                    "occurredAt" to row.occurredAt?.toString(),
                )
            )
            ok = client.post(webhookUrl, payload)
        } catch (e: Exception) {
            retryBackoff.onFailure(row, e.message)
            repository.save(row)
            return
        }
        if (ok) {
            row.status = "SENT"
            row.sentAt = Instant.now()
            row.errorMessage = null
            row.nextRetryAt = null
        } else {
            retryBackoff.onFailure(row, "webhook post failed")
        }
        repository.save(row)
    }

    /** M17 兼容重载（Task 4 迁移前 NotificationService 仍传 recipientIds/occurredAt）：
     *  写入行字段后委托 send(row)，保证 R-M18-5 重建路径一致。 */
    fun send(row: Notification, recipientIds: List<Long>, occurredAt: Instant) {
        row.recipientIds = recipientIds.joinToString(",")
        row.occurredAt = occurredAt
        send(row)
    }
}
