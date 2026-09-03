package com.example.compliance.report.infrastructure

import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.report.domain.ReportTemplateVersion
import org.springframework.data.jpa.repository.JpaRepository

interface ReportTemplateVersionRepository : JpaRepository<ReportTemplateVersion, Long> {
    fun findByTemplateIdOrderByVersionNoDesc(templateId: Long): List<ReportTemplateVersion>
    fun findFirstByTemplateIdAndStatusOrderByIdDesc(templateId: Long, status: VersionStatus): ReportTemplateVersion?
}
