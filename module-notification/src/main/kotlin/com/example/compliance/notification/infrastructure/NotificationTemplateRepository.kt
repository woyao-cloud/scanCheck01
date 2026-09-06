package com.example.compliance.notification.infrastructure

import com.example.compliance.notification.domain.NotificationTemplate
import org.springframework.data.jpa.repository.JpaRepository

interface NotificationTemplateRepository : JpaRepository<NotificationTemplate, Long> {
    fun findByTemplateType(templateType: String): NotificationTemplate?
}
