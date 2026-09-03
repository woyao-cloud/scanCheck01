package com.example.compliance.report.application

import com.example.compliance.result.application.FindingView
import com.example.compliance.result.domain.FindingStatus
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/** M9：统一指标模型——各报表口径一致。 */
class ReportMetricsTest {

    // R-9.6-a: view() 增益 ruleCode 参数，四视图用互异 "R1".."R4"，distinctBy{ruleCode}.size=4 → coverage=100。
    private fun view(status: FindingStatus, severity: String, ruleCode: String) = FindingView(
        1L, 9L, 1L, ruleCode, severity, status, "A.java", 1, Instant.now(), Instant.now(), 1, "STUB",
    )

    @Test
    fun `metrics aggregate by status and severity`() {
        val views = listOf(
            view(FindingStatus.NEW, "HIGH", "R1"),
            view(FindingStatus.NEW, "HIGH", "R2"),
            view(FindingStatus.FIXED, "LOW", "R3"),
            view(FindingStatus.WAIVED, "MEDIUM", "R4"),
        )
        val metrics = ReportMetrics.from(views, null, 4)

        assertEquals(2, metrics.openCount)
        assertEquals(1, metrics.fixedCount)
        assertEquals(1, metrics.waivedCount)
        assertEquals(2, metrics.bySeverity["HIGH"])
        assertEquals(2, metrics.byStatus[FindingStatus.NEW])
        assertEquals(100, metrics.coveragePercent.toInt())
    }
}
