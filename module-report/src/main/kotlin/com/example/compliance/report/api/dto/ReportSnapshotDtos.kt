package com.example.compliance.report.api.dto

import com.example.compliance.report.domain.ReportSnapshot
import java.time.Instant

data class GenerateRequest(
    val projectId: Long? = null,
    val scanTaskId: Long? = null,
)

/** 列表视图（不含 payload，保持列表轻量）。 */
data class SnapshotSummaryView(
    val id: Long,
    val templateVersionNo: Int,
    val projectId: Long?,
    val scanTaskId: Long?,
    val checklistVersionId: Long?,
    val snapshotType: String,
    val generatedAt: String,
) {
    companion object {
        fun from(s: ReportSnapshot) = SnapshotSummaryView(
            s.id!!, s.templateVersionNo, s.projectId, s.scanTaskId, s.checklistVersionId,
            s.snapshotType, s.generatedAt.toString(),
        )
    }
}

/** 详情视图（含 payload 原文，供展示/导出）。 */
data class SnapshotView(
    val id: Long,
    val templateVersionNo: Int,
    val projectId: Long?,
    val scanTaskId: Long?,
    val checklistVersionId: Long?,
    val snapshotType: String,
    val generatedAt: String,
    val payload: String,
) {
    companion object {
        fun from(s: ReportSnapshot) = SnapshotView(
            s.id!!, s.templateVersionNo, s.projectId, s.scanTaskId, s.checklistVersionId,
            s.snapshotType, s.generatedAt.toString(), s.payload,
        )
    }
}
