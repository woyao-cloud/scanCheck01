package com.example.compliance.scan.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*

@Entity
@Table(name = "scan_execution_log")
class ScanExecutionLog : BaseEntity() {
    @Column(name = "scan_task_id", nullable = false)
    var scanTaskId: Long = 0
    @Column(name = "stage", nullable = false, length = 32)
    lateinit var stage: String
    @Column(name = "level", nullable = false, length = 8)
    var level: String = "INFO"
    @Column(name = "message")
    var message: String? = null
}
