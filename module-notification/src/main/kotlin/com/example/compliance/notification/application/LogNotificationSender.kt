package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** 占位发送器（spec §6.4 点名）：写日志 + 委托落库（站内信表预留）。@Primary —— 事件消费方按接口注入拿到此 bean。 */
@Service
@org.springframework.context.annotation.Primary
class LogNotificationSender(
    private val notificationService: NotificationService,
) : NotificationSender {
    private val log = LoggerFactory.getLogger(LogNotificationSender::class.java)

    override fun send(channel: Channel, subject: String, body: String, recipients: List<Long>) {
        log.info("notification placeholder: channel={} subject={} recipients={}", channel, subject, recipients)
        notificationService.send(channel, subject, body, recipients)
    }
}
