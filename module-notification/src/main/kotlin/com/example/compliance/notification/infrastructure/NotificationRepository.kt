package com.example.compliance.notification.infrastructure

import com.example.compliance.notification.domain.Notification
import org.springframework.data.jpa.repository.JpaRepository

interface NotificationRepository : JpaRepository<Notification, Long> {
    fun findByStatusAndChannel(status: String, channel: String): List<Notification>
    fun findByRecipient(recipient: String): List<Notification>
}
