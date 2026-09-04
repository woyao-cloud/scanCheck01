package com.example.compliance.report.application

import java.math.BigDecimal

data class ScanSummary(
    val scanTaskId: Long,
    val engine: String,
    val status: String,
    val findingCount: Int,
    val bySeverity: Map<String, Int>,
)

data class ItemSummary(val itemCode: String, val result: String, val findingCount: Int)

data class ComplianceSummary(
    val projectId: Long,
    val evaluationId: Long,
    val score: BigDecimal?,
    val totalItems: Int,
    val passed: Int,
    val failed: Int,
    val warning: Int,
    val manual: Int,
    val skipped: Int,
    val items: List<ItemSummary>,
    val checklistVersionId: Long? = null,   // M12: 快照可追溯引用（spec P3-D3）
)

data class TrendPoint(val evaluatedAt: String, val score: BigDecimal?, val failed: Int)
