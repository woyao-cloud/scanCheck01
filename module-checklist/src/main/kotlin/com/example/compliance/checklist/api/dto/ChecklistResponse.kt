package com.example.compliance.checklist.api.dto

import com.example.compliance.checklist.domain.ChecklistItem
import com.example.compliance.checklist.domain.ChecklistVersion
import com.example.compliance.checklist.domain.ComplianceChecklist
import com.example.compliance.checklist.domain.ComplianceStandard
import com.example.compliance.checklist.domain.ProjectChecklistBinding

data class StandardResponse(
    val id: Long, val code: String, val name: String, val description: String?,
) { companion object { fun from(s: ComplianceStandard) = StandardResponse(s.id!!, s.code, s.name, s.description) } }

data class ChecklistResponse(
    val id: Long, val standardId: Long, val code: String, val name: String,
) { companion object { fun from(c: ComplianceChecklist) = ChecklistResponse(c.id!!, c.standardId, c.code, c.name) } }

data class VersionResponse(
    val id: Long, val checklistId: Long, val versionNo: String, val status: String, val publishedAt: java.time.Instant?,
) {
    companion object { fun from(v: ChecklistVersion) = VersionResponse(v.id!!, v.checklistId, v.versionNo, v.status.name, v.publishedAt) }
}

data class ItemResponse(
    val id: Long, val versionId: Long, val itemCode: String, val name: String,
    val category: String?, val riskLevel: String, val required: Boolean, val waivable: Boolean,
) {
    companion object {
        fun from(i: ChecklistItem) = ItemResponse(
            i.id!!, i.versionId, i.itemCode, i.name, i.category, i.riskLevel, i.required, i.waivable,
        )
    }
}

data class BindingResponse(val id: Long, val projectId: Long, val checklistVersionId: Long) {
    companion object { fun from(b: ProjectChecklistBinding) = BindingResponse(b.id!!, b.projectId, b.checklistVersionId) }
}
