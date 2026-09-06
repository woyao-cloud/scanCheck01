package com.example.compliance.common.event

/** 报告快照生成事件（spec M17 §3.4）：module-report 快照 persist 后发布。
 *  projectId 可空（Ruling PL-M17-2）：SCAN_SUMMARY 快照无单一项目，null 时监听器不通知。 */
data class ReportSnapshotGeneratedEvent(
    val snapshotId: Long,
    val projectId: Long?,
    val snapshotType: String,
)
