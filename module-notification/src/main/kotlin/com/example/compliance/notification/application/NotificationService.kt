package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 通知服务：落库实现（站内信表预留）。M10 I4：升级为 NotificationSender 实现，每个接收人一行。 */
@Service
class NotificationService(
    private val repository: NotificationRepository,
) : NotificationSender {

    // send 经 LogNotificationSender 委托（Spring 代理）调用 → 代理上 @Transactional 生效，单事务批量落库
    // （self-invocation 绕过代理：persist 的 @Transactional 在 send 内不生效，故 send 自身必须标注）
    // REQUIRES_NEW：通知写入必须在独立事务 —— 发布方事务内同步 @EventListener 调用本方法时，
    // 若加入发布方事务（REQUIRED），persist 失败会把发布方事务标 rollback-only，提交时抛
    // UnexpectedRollbackException，违反 spec §6.4 best-effort（失败不影响主流程）。
    // REQUIRES_NEW 挂起发布方事务、通知失败仅回滚通知自身（final-review Finding 2 硬化）。
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun send(channel: Channel, subject: String, body: String, recipients: List<Long>) {
        recipients.forEach { persist(channel, it.toString(), subject, body) }
    }

    @Transactional
    fun persist(channel: Channel, recipient: String, title: String, content: String?): Notification {
        val pending = repository.save(Notification().apply {
            this.channel = channel.name
            this.recipient = recipient
            type = "EVENT"
            this.title = title
            this.content = content
            status = "PENDING"
        })
        // 渠道适配器扩展点：M9/M10 直接视为发送成功
        pending.status = "SENT"
        pending.sentAt = Instant.now()
        return repository.save(pending)
    }

    @Transactional(readOnly = true)
    fun list(recipient: String?): List<Notification> =
        if (recipient.isNullOrBlank()) repository.findAll()
        else repository.findByRecipient(recipient)
}
