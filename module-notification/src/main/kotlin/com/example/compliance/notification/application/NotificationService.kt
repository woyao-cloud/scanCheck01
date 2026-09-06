package com.example.compliance.notification.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.example.compliance.user.infrastructure.UserRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** M17 通知服务（spec R-M17-D4）：事件 → 站内信落库 + EMAIL/Webhook 真实渠道投递。
 *  REQUIRES_NEW：通知写入必须在独立事务 —— 发布方事务内同步 @EventListener 调用本方法时，
 *  通知失败仅回滚通知自身，绝不标记发布方事务 rollback-only（spec §6.4 best-effort）。
 *  M18 §4.6 (R-M18-4)：签名改 notify(type, variables, recipients)，渲染在 service（所有渠道/收件人共用一次）；
 *  M6：emailSender.isAvailable() 提升出循环（每事件评估一次）。 */
@Service
class NotificationService(
    private val repository: NotificationRepository,
    private val userRepository: UserRepository,
    private val emailSender: EmailSender,
    private val webhookSender: WebhookSender,
    private val renderer: TemplateRenderer,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun notify(notificationType: String, variables: Map<String, Any?>, recipients: List<Long>) {
        val unique = recipients.distinct()
        if (unique.isEmpty()) return
        val occurredAt = Instant.now()
        val (title, body) = renderer.render(notificationType, variables)
        val emailAvailable = emailSender.isAvailable()
        unique.forEach { userId ->
            // IN_APP：每收件人一行，落库即投递（SENT + sentAt）
            repository.save(Notification().apply {
                channel = Channel.IN_APP.name
                recipient = userId.toString()
                type = notificationType
                this.title = title
                content = body
                status = "SENT"
                sentAt = occurredAt
            })
            // EMAIL：仅对有邮箱用户建行；mail sender 未配置 → 跳过（镜像 webhook 未配置，Ruling PL-M17-3）
            if (emailAvailable) {
                val email = userRepository.findById(userId).orElse(null)?.email
                if (!email.isNullOrBlank()) {
                    val row = repository.save(Notification().apply {
                        channel = Channel.EMAIL.name
                        recipient = email
                        type = notificationType
                        this.title = title
                        content = body
                        status = "PENDING"
                    })
                    emailSender.send(row)   // sender 自身吞掉异常 → SENT/FAILED，不抛出
                }
            }
        }
        // WEBHOOK：每事件一行；url 未配置 → 跳过
        if (webhookSender.isConfigured()) {
            val row = repository.save(Notification().apply {
                channel = Channel.WEBHOOK.name
                recipient = "webhook"
                type = notificationType
                this.title = title
                content = body
                status = "PENDING"
                // R-M18-5：payload 重建字段落行 —— 重试与首投同路径
                recipientIds = unique.joinToString(",")
                this.occurredAt = occurredAt
            })
            webhookSender.send(row)
        }
    }

    @Transactional(readOnly = true)
    fun listMy(userId: Long, page: Int, size: Int, unreadOnly: Boolean): Page<Notification> {
        // 硬化（镜像 report list C2 / 审计查询 D6）：负 page 拒绝 400，size 钳制 [1,100]，固定 id 倒序
        if (page < 0) throw BusinessException(400, "page must be non-negative")
        val pageable = PageRequest.of(page, size.coerceIn(1, 100), Sort.by(Sort.Direction.DESC, "id"))
        val recipient = userId.toString()
        return if (unreadOnly) {
            repository.findByRecipientAndChannelAndReadAtIsNull(recipient, Channel.IN_APP.name, pageable)
        } else {
            repository.findByRecipientAndChannel(recipient, Channel.IN_APP.name, pageable)
        }
    }

    @Transactional(readOnly = true)
    fun unreadCount(userId: Long): Long =
        repository.countByRecipientAndChannelAndReadAtIsNull(userId.toString(), Channel.IN_APP.name)

    @Transactional
    fun markRead(id: Long, userId: Long) {
        val row = repository.findByIdAndRecipientAndChannel(id, userId.toString(), Channel.IN_APP.name)
            ?: throw BusinessException(404, "notification not found: $id")
        if (row.readAt == null) {
            row.readAt = Instant.now()
            repository.save(row)
        }
    }

    @Transactional
    fun markReadAll(userId: Long): Int =
        repository.markReadAll(userId.toString(), Channel.IN_APP.name, Instant.now())
}
