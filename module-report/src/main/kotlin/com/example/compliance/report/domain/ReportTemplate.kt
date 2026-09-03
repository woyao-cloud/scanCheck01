package com.example.compliance.report.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Version

/** 报告模板主线：每类型一条（SCAN_SUMMARY/COMPLIANCE/TREND），版本历史在 ReportTemplateVersion。 */
@Entity
@Table(name = "report_template")
class ReportTemplate : BaseEntity() {
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
