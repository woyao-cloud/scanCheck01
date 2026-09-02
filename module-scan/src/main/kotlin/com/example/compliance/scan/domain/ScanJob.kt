package com.example.compliance.scan.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "scan_job")
class ScanJob : BaseEntity() {
    @Column(name = "scan_task_id", nullable = false)
    var scanTaskId: Long = 0
    @Column(name = "engine", nullable = false, length = 32)
    lateinit var engine: String
    @Column(name = "job_status", nullable = false, length = 16)
    var jobStatus: String = "PENDING"
    @Column(name = "started_at")
    var startedAt: Instant? = null
    @Column(name = "finished_at")
    var finishedAt: Instant? = null
    @Column(name = "duration_ms", nullable = false)
    var durationMs: Long = 0
    @Column(name = "finding_count", nullable = false)
    var findingCount: Int = 0
    @Column(name = "error_message", length = 512)
    var errorMessage: String? = null
}
