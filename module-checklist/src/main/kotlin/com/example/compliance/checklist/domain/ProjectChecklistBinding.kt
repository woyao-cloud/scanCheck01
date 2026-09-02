package com.example.compliance.checklist.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "project_checklist_binding")
class ProjectChecklistBinding : BaseEntity() {
    @Column(name = "project_id", nullable = false)
    var projectId: Long = 0
    @Column(name = "checklist_version_id", nullable = false)
    var checklistVersionId: Long = 0
    @Column(name = "bound_at", nullable = false)
    var boundAt: Instant = Instant.now()
    @Column(name = "bound_by")
    var boundBy: Long? = null
}
