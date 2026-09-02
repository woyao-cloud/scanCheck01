package com.example.compliance.checklist.infrastructure

import com.example.compliance.checklist.domain.ChecklistItem
import com.example.compliance.checklist.domain.ChecklistVersion
import com.example.compliance.checklist.domain.ComplianceChecklist
import com.example.compliance.checklist.domain.ComplianceStandard
import com.example.compliance.checklist.domain.ProjectChecklistBinding
import com.example.compliance.checklist.domain.VersionStatus
import org.springframework.data.jpa.repository.JpaRepository

interface StandardRepository : JpaRepository<ComplianceStandard, Long> {
    fun findByCode(code: String): ComplianceStandard?
    fun existsByCode(code: String): Boolean
}

interface ChecklistRepository : JpaRepository<ComplianceChecklist, Long> {
    fun findByCode(code: String): ComplianceChecklist?
    fun existsByCode(code: String): Boolean
}

interface ChecklistVersionRepository : JpaRepository<ChecklistVersion, Long> {
    fun findByChecklistIdOrderByVersionNoDesc(checklistId: Long): List<ChecklistVersion>
    fun findFirstByChecklistIdAndStatusOrderByIdDesc(checklistId: Long, status: VersionStatus): ChecklistVersion?
}

interface ChecklistItemRepository : JpaRepository<ChecklistItem, Long> {
    fun findByVersionId(versionId: Long): List<ChecklistItem>
}

interface BindingRepository : JpaRepository<ProjectChecklistBinding, Long> {
    fun findFirstByProjectIdOrderByIdDesc(projectId: Long): ProjectChecklistBinding?
}
