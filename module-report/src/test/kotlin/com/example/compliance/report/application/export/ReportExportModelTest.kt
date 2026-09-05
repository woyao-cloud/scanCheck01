package com.example.compliance.report.application.export

import kotlin.test.Test
import kotlin.test.assertEquals

/** M16 (R-M16-D2)：payload → 行模型——类型感知布局 + 根元素对象/数组双形态 + 兜底。 */
class ReportExportModelTest {

    @Test
    fun `scan summary maps severity and headers`() {
        val payload = """
            {"scanTaskId":77,"engine":"SEMGREP","status":"SUCCESS","findingCount":3,
             "bySeverity":{"CRITICAL":1,"HIGH":0,"MEDIUM":2,"LOW":0}}
        """.trimIndent()
        val sheets = ReportExportModel.sheetsFor("SCAN_SUMMARY", payload)
        assertEquals(1, sheets.size)
        assertEquals("ScanSummary", sheets[0].name)
        val rows = sheets[0].rows
        assertEquals(listOf("ScanTaskId", "Engine", "Status", "FindingCount", "Critical", "High", "Medium", "Low"), rows[0])
        assertEquals(listOf("77", "SEMGREP", "SUCCESS", "3", "1", "0", "2", "0"), rows[1])
    }

    @Test
    fun `compliance maps summary and items`() {
        val payload = """
            {"projectId":5,"evaluationId":7,"score":88.5,"totalItems":2,"passed":1,"failed":1,
             "warning":0,"manual":0,"skipped":0,"checklistVersionId":9,
             "items":[{"itemCode":"IT-1","result":"PASS","findingCount":0},
                      {"itemCode":"IT-2","result":"FAIL","findingCount":3}]}
        """.trimIndent()
        val sheets = ReportExportModel.sheetsFor("COMPLIANCE", payload)
        assertEquals(2, sheets.size)
        assertEquals("Summary", sheets[0].name)
        assertEquals("Items", sheets[1].name)
        assertEquals(listOf("5", "7", "88.5", "2", "1", "1", "0", "0", "0", "9"), sheets[0].rows[1])
        assertEquals(listOf("ItemCode", "Result", "FindingCount"), sheets[1].rows[0])
        assertEquals(listOf("IT-1", "PASS", "0"), sheets[1].rows[1])
        assertEquals(listOf("IT-2", "FAIL", "3"), sheets[1].rows[2])
    }

    @Test
    fun `trend maps top-level array and null score to empty`() {
        val payload = """[{"evaluatedAt":"2026-09-01T00:00:00Z","score":null,"failed":2},
                          {"evaluatedAt":"2026-09-02T00:00:00Z","score":80.5,"failed":1}]"""
        val sheets = ReportExportModel.sheetsFor("TREND", payload)
        assertEquals(1, sheets.size)
        assertEquals("Trend", sheets[0].name)
        val rows = sheets[0].rows
        assertEquals(listOf("EvaluatedAt", "Score", "Failed"), rows[0])
        assertEquals(listOf("2026-09-01T00:00:00Z", "", "2"), rows[1])
        assertEquals(listOf("2026-09-02T00:00:00Z", "80.5", "1"), rows[2])
    }

    @Test
    fun `unknown type with object payload falls back to key value`() {
        val sheets = ReportExportModel.sheetsFor("UNKNOWN", """{"a":1,"b":null}""")
        val rows = sheets[0].rows
        assertEquals(listOf("Key", "Value"), rows[0])
        assertEquals(listOf("a", "1"), rows[1])
        assertEquals(listOf("b", ""), rows[2])
    }

    @Test
    fun `unknown type with array payload falls back to index value`() {
        val sheets = ReportExportModel.sheetsFor("UNKNOWN", """["x","y"]""")
        val rows = sheets[0].rows
        assertEquals(listOf("Index", "Value"), rows[0])
        assertEquals(listOf("0", "x"), rows[1])
        assertEquals(listOf("1", "y"), rows[2])
    }

    @Test
    fun `invalid payload with scan type renders empty scan sheet`() {
        val sheets = ReportExportModel.sheetsFor("SCAN_SUMMARY", "{not json")
        val rows = sheets[0].rows
        assertEquals(listOf("ScanTaskId", "Engine", "Status", "FindingCount", "Critical", "High", "Medium", "Low"), rows[0])
        assertEquals(listOf("", "", "", "", "0", "0", "0", "0"), rows[1])
    }

    @Test
    fun `invalid payload with unknown type renders header only`() {
        val sheets = ReportExportModel.sheetsFor("UNKNOWN", "{not json")
        assertEquals(listOf("Key", "Value"), sheets[0].rows[0])
        assertEquals(1, sheets[0].rows.size)
    }
}
