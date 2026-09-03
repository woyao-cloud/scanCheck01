package com.example.compliance.common.event

/** 豁免事件（spec §4.2 WAIVED 终态 + §6.4）：module-remediation 状态转移至 WAIVED 时发布。 */
data class RemediationWaiverEvent(
    val projectId: Long,
    val findingId: Long,
    val actorId: Long,
    val reason: String,
)
