package com.example.compliance.scan.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "scan_task")
class ScanTask : BaseEntity() {
    @Column(name = "project_id", nullable = false)
    var projectId: Long = 0
    @Column(name = "repo_id")
    var repoId: Long? = null
    @Column(name = "engine", nullable = false, length = 32)
    lateinit var engine: String
    @Column(name = "ref", length = 128)
    var ref: String? = null
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: ScanTaskStatus = ScanTaskStatus.PENDING
    @Column(name = "trigger_type", nullable = false, length = 16)
    var triggerType: String = "MANUAL"
    @Column(name = "created_by")
    var createdBy: Long? = null
    @Column(name = "started_at")
    var startedAt: Instant? = null
    @Column(name = "finished_at")
    var finishedAt: Instant? = null
    @Column(name = "error_message", length = 512)
    var errorMessage: String? = null
    @Column(name = "finding_count", nullable = false)
    var findingCount: Int = 0
}
