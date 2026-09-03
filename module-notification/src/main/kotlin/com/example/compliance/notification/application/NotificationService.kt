package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 通知服务：M9 先落库并标记 SENT（桩），真实渠道（邮件/webhook）为后续扩展点。 */
@Service
class NotificationService(
    private val repository: NotificationRepository,
) {
    @Transactional
    fun send(channel: String, recipient: String, type: String, title: String, content: String?): Notification {
        val pending = repository.save(Notification().apply {
            this.channel = channel
            this.recipient = recipient
            this.type = type
            this.title = title
            this.content = content
            status = "PENDING"
        })
        // 渠道适配器扩展点：M9 直接视为发送成功
        pending.status = "SENT"
        pending.sentAt = Instant.now()
        return repository.save(pending)
    }

    @Transactional(readOnly = true)
    fun list(recipient: String?): List<Notification> =
        if (recipient.isNullOrBlank()) repository.findAll()
        else repository.findByRecipient(recipient)
}
