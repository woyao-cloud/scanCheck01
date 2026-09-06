package com.example.compliance.notification.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Version

/** 通知模板主线：每类型一条（6 固定类型），版本历史在 NotificationTemplateVersion。镜像 ReportTemplate。 */
@Entity
@Table(name = "notification_template")
class NotificationTemplate : BaseEntity() {
    @Column(name = "template_type", nullable = false, length = 32)
    lateinit var templateType: String
    @Column(name = "name", nullable = false, length = 128)
    lateinit var name: String
    @Column(name = "description")
    var description: String? = null
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
