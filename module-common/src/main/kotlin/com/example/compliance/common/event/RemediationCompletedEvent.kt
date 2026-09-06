package com.example.compliance.common.event

/** 整改完成事件（spec M17 §3.4）：module-remediation fixed 命令成功时发布。assigneeId 可空（未派单）。 */
data class RemediationCompletedEvent(
    val findingId: Long,
    val projectId: Long,
    val actorId: Long,
    val assigneeId: Long?,
)
