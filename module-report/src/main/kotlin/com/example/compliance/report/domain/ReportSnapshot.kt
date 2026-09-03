package com.example.compliance.report.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** 报告快照：生成时固化指标数据（payload JSONB），只增不改不删。 */
@Entity
@Table(name = "report_snapshot")
class ReportSnapshot : BaseEntity() {
    @Column(name = "template_id", nullable = false)
    var templateId: Long = 0
    @Column(name = "template_version_no", nullable = false)
    var templateVersionNo: Int = 0
    @Column(name = "project_id")
    var projectId: Long? = null
    @Column(name = "scan_task_id")
    var scanTaskId: Long? = null
    @Column(name = "checklist_version_id")
    var checklistVersionId: Long? = null
    @Column(name = "snapshot_type", nullable = false, length = 32)
    lateinit var snapshotType: String
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    lateinit var payload: String
    @Column(name = "generated_by")
    var generatedBy: Long? = null
    @Column(name = "generated_at", nullable = false)
    lateinit var generatedAt: Instant
}
