package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel

/** 通知发送契约（spec §6.4 逐字）。实现：LogNotificationSender（@Primary，写日志 + 落库）。 */
interface NotificationSender {
    fun send(channel: Channel, subject: String, body: String, recipients: List<Long>)
}
