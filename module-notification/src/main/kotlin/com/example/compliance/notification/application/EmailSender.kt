package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Notification
import com.example.compliance.notification.infrastructure.NotificationRepository
import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service
import java.time.Instant

/** EMAIL 渠道适配器（spec R-M17-D5 seam）：依赖 Spring JavaMailSender 接口。
 *  未配置 spring.mail.*（无 JavaMailSender bean）→ isAvailable()=false，不建 EMAIL 行。
 *  send 绝不抛出 —— 所有异常置 FAILED（+retry_count+error_message），保护 notify 的 REQUIRES_NEW 事务。 */
@Service
class EmailSender(
    private val mailSenderProvider: ObjectProvider<JavaMailSender>,
    private val repository: NotificationRepository,
) {
    fun isAvailable(): Boolean = mailSenderProvider.getIfAvailable() != null

    fun send(row: Notification) {
        val mailSender = mailSenderProvider.getIfAvailable()
        if (mailSender == null) {
            fail(row, "mail sender not configured")
            return
        }
        try {
            val mime = mailSender.createMimeMessage()
            mime.setFrom(InternetAddress("no-reply@example.com"))
            mime.setRecipients(Message.RecipientType.TO, row.recipient)
            mime.setSubject(row.title, "UTF-8")
            mime.setText(row.content ?: "", "UTF-8")
            mailSender.send(mime)
            row.status = "SENT"
            row.sentAt = Instant.now()
        } catch (e: Exception) {
            fail(row, e.message)
        }
        repository.save(row)
    }

    private fun fail(row: Notification, message: String?) {
        row.status = "FAILED"
        row.retryCount++
        row.errorMessage = message?.take(500)
        repository.save(row)
    }
}
