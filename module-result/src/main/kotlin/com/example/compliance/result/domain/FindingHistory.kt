package com.example.compliance.result.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

/** finding_history：扫描出现历史（只增不改）。action: CREATED（首次发现）/ REAPPEARED（复扫复现）。 */
@Entity
@Table(name = "finding_history")
class FindingHistory : BaseEntity() {
    @Column(name = "finding_id", nullable = false)
    var findingId: Long = 0
    @Column(name = "scan_task_id", nullable = false)
    var scanTaskId: Long = 0
    @Column(name = "action", nullable = false, length = 16)
    lateinit var action: String
    @Column(name = "changed_by")
    var changedBy: Long? = null
    @Column(name = "changed_at", nullable = false)
    var changedAt: java.time.Instant = java.time.Instant.now()
    @Column(name = "detail")
    var detail: String? = null
}
