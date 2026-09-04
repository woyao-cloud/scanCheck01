package com.example.compliance.report.api.dto

import com.example.compliance.report.domain.ReportTemplateVersion
import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.constraints.NotNull

data class DraftRequest(
    val name: String? = null,
    @field:NotNull
    val sections: JsonNode? = null,
)

data class TemplateVersionView(
    val templateId: Long,
    val versionNo: Int,
    val status: String,
    val sections: String,
) {
    companion object {
        fun from(v: ReportTemplateVersion) = TemplateVersionView(v.templateId, v.versionNo, v.status.name, v.sections)
    }
}
