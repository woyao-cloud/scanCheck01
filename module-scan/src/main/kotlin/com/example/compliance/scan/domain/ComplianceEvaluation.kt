package com.example.compliance.scan.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*
import java.math.BigDecimal

@Entity
@Table(name = "compliance_evaluation")
class ComplianceEvaluation : BaseEntity() {
    @Column(name = "scan_task_id", nullable = false)
    var scanTaskId: Long = 0
    @Column(name = "project_id", nullable = false)
    var projectId: Long = 0
    @Column(name = "checklist_version_id")
    var checklistVersionId: Long? = null
    @Column(name = "total_items", nullable = false)
    var totalItems: Int = 0
    @Column(name = "passed", nullable = false)
    var passed: Int = 0
    @Column(name = "failed", nullable = false)
    var failed: Int = 0
    @Column(name = "warning", nullable = false)
    var warning: Int = 0
    @Column(name = "manual", nullable = false)
    var manual: Int = 0
    @Column(name = "skipped", nullable = false)
    var skipped: Int = 0
    @Column(name = "score", precision = 5, scale = 2)
    var score: BigDecimal? = null
}
