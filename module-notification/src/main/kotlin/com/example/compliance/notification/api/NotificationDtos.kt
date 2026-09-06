package com.example.compliance.notification.api

import com.example.compliance.notification.domain.Notification
import java.time.Instant

/** 站内信视图（spec §3.5）。 */
data class NotificationView(
    val id: Long,
    val type: String,
    val title: String,
    val content: String?,
    val channel: String,
    val status: String,
    val readAt: Instant?,
    val createdAt: Instant?,
) {
    companion object {
        fun from(e: Notification) = NotificationView(
            id = e.id!!, type = e.type, title = e.title, content = e.content,
            channel = e.channel, status = e.status, readAt = e.readAt, createdAt = e.createdAt,
        )
    }
}

/** 未读计数 / 标记数（{count} 形状）。 */
data class UnreadCount(val count: Long)
