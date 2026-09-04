package com.example.compliance.report.application

import com.example.compliance.report.domain.ReportSnapshot
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlReportRendererTest {
    @Test
    fun `render produces readable html with type id and payload values`() {
        val snapshot = ReportSnapshot().apply {
            id = 3L; templateId = 1L; templateVersionNo = 2; snapshotType = "SCAN_SUMMARY"
            payload = """{"findingCount":3,"engine":"SEMGREP","bySeverity":{"HIGH":2}}"""
            generatedAt = Instant.parse("2026-09-03T10:00:00Z")
        }
        val html = HtmlReportRenderer.render(snapshot)
        assertTrue(html.contains("SCAN_SUMMARY"))
        assertTrue(html.contains("Report #3"))
        assertTrue(html.contains("findingCount"))
        assertTrue(html.contains("3"))
        assertTrue(html.contains("template v2"))
        // 未转义注入不出现原始尖括号（escape 生效）
        assertFalse(html.contains("<script>"))
    }

    @Test
    fun `render escapes html in payload values`() {
        val snapshot = ReportSnapshot().apply {
            id = 4L; templateId = 1L; templateVersionNo = 1; snapshotType = "COMPLIANCE"
            payload = """{"message":"<script>alert(1)</script>"}"""
            generatedAt = Instant.now()
        }
        assertFalse(HtmlReportRenderer.render(snapshot).contains("<script>"))
    }
}
