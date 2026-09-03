package com.example.compliance.report.infrastructure

import com.example.compliance.report.domain.ReportTemplate
import org.springframework.data.jpa.repository.JpaRepository

interface ReportTemplateRepository : JpaRepository<ReportTemplate, Long> {
    fun findByTemplateType(templateType: String): ReportTemplate?
}
