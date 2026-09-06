package com.example.compliance.notification.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/** 通知模板版本：DRAFT→PUBLISHED→DISABLED。content 为 JSONB `{"title": "...", "body": "..."}`（镜像 report 先例）。 */
@Entity
@Table(name = "notification_template_version")
class NotificationTemplateVersion : BaseEntity() {
    @Column(name = "template_id", nullable = false)
    var templateId: Long = 0
    @Column(name = "version_no", nullable = false)
    var versionNo: Int = 0
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: TemplateStatus = TemplateStatus.DRAFT
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", columnDefinition = "jsonb")
    lateinit var content: String
    @Column(name = "created_by")
    var createdBy: Long? = null
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
