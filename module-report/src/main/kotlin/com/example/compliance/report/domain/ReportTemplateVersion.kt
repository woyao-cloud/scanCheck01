package com.example.compliance.report.domain

import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/** 报告模板版本：DRAFT→PUBLISHED→DISABLED。sections 为 JSONB（Ruling #13/#25 必须 @JdbcTypeCode）。 */
@Entity
@Table(name = "report_template_version")
class ReportTemplateVersion : BaseEntity() {
    @Column(name = "template_id", nullable = false)
    var templateId: Long = 0
    @Column(name = "version_no", nullable = false)
    var versionNo: Int = 0
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: VersionStatus = VersionStatus.DRAFT
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sections", columnDefinition = "jsonb")
    lateinit var sections: String
    @Column(name = "created_by")
    var createdBy: Long? = null
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
