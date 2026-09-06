package com.example.compliance.notification.infrastructure

import com.example.compliance.notification.domain.NotificationTemplateVersion
import com.example.compliance.notification.domain.TemplateStatus
import org.springframework.data.jpa.repository.JpaRepository

interface NotificationTemplateVersionRepository : JpaRepository<NotificationTemplateVersion, Long> {
    fun findByTemplateIdOrderByVersionNoDesc(templateId: Long): List<NotificationTemplateVersion>
    fun findFirstByTemplateIdAndStatusOrderByIdDesc(templateId: Long, status: TemplateStatus): NotificationTemplateVersion?
}
