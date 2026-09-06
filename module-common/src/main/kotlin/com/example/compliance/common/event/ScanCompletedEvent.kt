package com.example.compliance.common.event

/** 扫描任务达终态事件（spec M17 §3.4）：module-scan 任务终态（SUCCESS/FAILED）时发布。
 *  接线点在 ScanOrchestrator（Ruling PL-M17-1：终态流转实际发生在编排器，非 ScanTaskService）。 */
data class ScanCompletedEvent(
    val scanTaskId: Long,
    val projectId: Long,
    val status: String,
)
