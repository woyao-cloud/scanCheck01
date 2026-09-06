package com.example.compliance.notification.infrastructure

import com.example.compliance.notification.domain.Notification
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface NotificationRepository : JpaRepository<Notification, Long> {
    fun findByStatusAndChannel(status: String, channel: String): List<Notification>
    fun findByRecipient(recipient: String): List<Notification>

    // M17 站内信中心（spec §3.3/§3.5）：按收件人 + IN_APP 渠道，id DESC 分页（service 传 Sort）
    fun findByRecipientAndChannel(recipient: String, channel: String, pageable: Pageable): Page<Notification>
    fun findByRecipientAndChannelAndReadAtIsNull(recipient: String, channel: String, pageable: Pageable): Page<Notification>
    fun countByRecipientAndChannelAndReadAtIsNull(recipient: String, channel: String): Long
    fun findByIdAndRecipientAndChannel(id: Long, recipient: String, channel: String): Notification?

    /** 批量已读（返回受影响行数，service 作为 {count} 返回）。 */
    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.recipient = :recipient AND n.channel = :channel AND n.readAt IS NULL")
    fun markReadAll(@Param("recipient") recipient: String, @Param("channel") channel: String, @Param("now") now: Instant): Int
}
