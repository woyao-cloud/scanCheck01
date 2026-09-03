package com.example.compliance.report.application

import com.example.compliance.result.application.FindingView
import com.example.compliance.result.domain.FindingStatus
import java.math.BigDecimal

/** 统一指标模型：所有报表（scanSummary/compliance/trend）共用同一口径。 */
data class ReportMetrics(
    val total: Int,
    val openCount: Int,
    val fixedCount: Int,
    val waivedCount: Int,
    val bySeverity: Map<String, Int>,
    val byStatus: Map<FindingStatus, Int>,
    val evaluationScore: BigDecimal?,
    val coveragePercent: BigDecimal,
) {
    companion object {
        /** 活动集（开放口径）：NEW/CONFIRMED/ASSIGNED/FIXING/RECHECKING。 */
        private val OPEN_STATES = setOf(
            FindingStatus.NEW, FindingStatus.CONFIRMED, FindingStatus.ASSIGNED,
            FindingStatus.FIXING, FindingStatus.RECHECKING,
        )

        fun from(
            views: List<FindingView>,
            score: BigDecimal?,
            publishedItemCount: Int,
        ): ReportMetrics {
            val byStatus = views.groupingBy { it.status }.eachCount()
            val open = views.count { it.status in OPEN_STATES }
            val fixed = byStatus[FindingStatus.FIXED] ?: 0
            val waived = (byStatus[FindingStatus.WAIVED] ?: 0) + (byStatus[FindingStatus.IGNORED] ?: 0)
            val bySeverity = views.groupingBy { it.severity }.eachCount()
            val coverage = if (publishedItemCount <= 0) BigDecimal.ZERO
                else BigDecimal(100.0 * views.distinctBy { it.ruleCode }.size / publishedItemCount)
                    .setScale(2, java.math.RoundingMode.HALF_UP)
            return ReportMetrics(
                total = views.size, openCount = open, fixedCount = fixed, waivedCount = waived,
                bySeverity = bySeverity, byStatus = byStatus,
                evaluationScore = score, coveragePercent = coverage,
            )
        }
    }
}
