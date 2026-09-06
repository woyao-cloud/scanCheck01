package com.example.compliance.common.event

/** 整改指派事件（spec M17 §3.4）：module-remediation assign 命令成功且首次指派给具体受让人时发布（Ruling PL-M17-6）。 */
data class RemediationAssignedEvent(
    val findingId: Long,
    val projectId: Long,
    val assigneeId: Long,
)
