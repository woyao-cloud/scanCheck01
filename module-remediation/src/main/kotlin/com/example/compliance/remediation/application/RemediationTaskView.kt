package com.example.compliance.remediation.application

import com.example.compliance.result.domain.FindingStatus
import java.time.Instant
import java.time.LocalDate

/** 整改任务视图（任务侧元数据；finding 状态在 FindingView，权威=finding.status）。 */
data class RemediationTaskView(
    val id: Long,
    val findingId: Long,
    val projectId: Long,
    val assigneeUserId: Long?,
    val createdBy: Long?,
    val plan: String?,
    val dueDate: LocalDate?,
    val status: FindingStatus,
    val createdAt: Instant,
)
