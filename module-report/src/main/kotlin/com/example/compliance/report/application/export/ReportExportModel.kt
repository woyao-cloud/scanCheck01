package com.example.compliance.report.application.export

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** 快照 payload → List<SheetDef>（spec R-M16-D2）：类型感知布局，SCAN_SUMMARY 单表 / COMPLIANCE 双表 /
 *  TREND 时序表 / 兜底键值表。根元素双形态：SCAN_SUMMARY/COMPLIANCE 为对象，TREND 为顶层 JSON 数组。 */
object ReportExportModel {
    private val mapper = ObjectMapper()

    private val SCAN_HEADER = listOf("ScanTaskId", "Engine", "Status", "FindingCount", "Critical", "High", "Medium", "Low")
    private val COMPLIANCE_SUMMARY_HEADER = listOf("ProjectId", "EvaluationId", "Score", "TotalItems", "Passed", "Failed", "Warning", "Manual", "Skipped", "ChecklistVersionId")
    private val COMPLIANCE_ITEMS_HEADER = listOf("ItemCode", "Result", "FindingCount")
    private val TREND_HEADER = listOf("EvaluatedAt", "Score", "Failed")
    private val FALLBACK_HEADER = listOf("Key", "Value")
    private val ARRAY_FALLBACK_HEADER = listOf("Index", "Value")

    fun sheetsFor(snapshotType: String, payload: String): List<SheetDef> {
        val root = runCatching { mapper.readTree(payload) }.getOrElse { mapper.nullNode() }
        return when (snapshotType) {
            "SCAN_SUMMARY" -> listOf(scanSummary(root))
            "COMPLIANCE" -> compliance(root)
            "TREND" -> listOf(trend(root))
            else -> listOf(fallback(root))
        }
    }

    private fun scanSummary(root: JsonNode): SheetDef {
        val bySeverity = root.get("bySeverity")?.takeUnless { it.isNull } ?: mapper.createObjectNode()
        fun sev(key: String): String = str(bySeverity.get(key) ?: bySeverity.get(key.lowercase()), "0")
        return SheetDef(
            "ScanSummary",
            listOf(
                SCAN_HEADER,
                listOf(
                    cell(root, "scanTaskId"), cell(root, "engine"), cell(root, "status"), cell(root, "findingCount"),
                    sev("CRITICAL"), sev("HIGH"), sev("MEDIUM"), sev("LOW"),
                ),
            ),
        )
    }

    private fun compliance(root: JsonNode): List<SheetDef> {
        val summaryRow = listOf(
            cell(root, "projectId"), cell(root, "evaluationId"), cell(root, "score"), cell(root, "totalItems"),
            cell(root, "passed"), cell(root, "failed"), cell(root, "warning"), cell(root, "manual"),
            cell(root, "skipped"), cell(root, "checklistVersionId"),
        )
        val itemRows = (root.get("items") ?: mapper.createArrayNode()).map { item ->
            listOf(cell(item, "itemCode"), cell(item, "result"), cell(item, "findingCount"))
        }
        return listOf(
            SheetDef("Summary", listOf(COMPLIANCE_SUMMARY_HEADER, summaryRow)),
            SheetDef("Items", listOf(COMPLIANCE_ITEMS_HEADER) + itemRows),
        )
    }

    private fun trend(root: JsonNode): SheetDef {
        val rows = if (root.isArray) {
            root.map { p -> listOf(cell(p, "evaluatedAt"), cell(p, "score"), cell(p, "failed")) }
        } else {
            emptyList()
        }
        return SheetDef("Trend", listOf(TREND_HEADER) + rows)
    }

    private fun fallback(root: JsonNode): SheetDef {
        val header: List<String>
        val rows: List<List<String>>
        when {
            root.isArray -> {
                header = ARRAY_FALLBACK_HEADER
                rows = root.mapIndexed { i, v -> listOf(i.toString(), if (v.isNull) "" else v.asText()) }
            }
            root.isObject -> {
                header = FALLBACK_HEADER
                rows = root.fields().asSequence().map { (k, v) -> listOf(k, if (v.isNull) "" else v.asText()) }.toList()
            }
            else -> {
                header = FALLBACK_HEADER
                rows = emptyList()
            }
        }
        return SheetDef("Data", listOf(header) + rows)
    }

    /** 字段取串：缺省/显式 null → 空串（JSON `"score":null` 的 NullNode.asText() 会产字面 "null"，必须拦）。 */
    private fun cell(root: JsonNode, name: String): String {
        val node = root.get(name) ?: return ""
        return if (node.isNull) "" else node.asText()
    }

    private fun str(node: JsonNode?, default: String): String = when {
        node == null || node.isNull || node.asText().isEmpty() -> default
        else -> node.asText()
    }
}
