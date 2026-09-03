package com.example.compliance.common.event

/** 回归检测事件（spec §6.4）：module-result 复扫验证命中回归时发布，module-notification 监听。 */
data class FindingRegressionEvent(
    val projectId: Long,
    val scanTaskId: Long,
    val findingIds: List<Long>,
)
