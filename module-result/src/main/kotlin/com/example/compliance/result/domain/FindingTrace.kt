package com.example.compliance.result.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*

@Entity
@Table(name = "finding_history")
class FindingTrace : BaseEntity() {
    @Column(name = "finding_id", nullable = false)
    var findingId: Long = 0
    @Column(name = "scan_task_id", nullable = false)
    var scanTaskId: Long = 0
    @Column(name = "action", nullable = false, length = 16)
    lateinit var action: String
}
