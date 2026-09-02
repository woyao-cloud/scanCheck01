package com.example.compliance.scan.api.dto

import com.example.compliance.result.domain.Finding
import com.example.compliance.scan.domain.ChecklistItemResult
import com.example.compliance.scan.domain.ComplianceEvaluation
import com.example.compliance.scan.domain.ScanTask
import java.math.BigDecimal

data class ScanResponse(
    val id: Long,
    val projectId: Long,
    val engine: String,
    val ref: String?,
    val status: String,
    val findingCount: Int,
    val errorMessage: String?,
) {
    companion object {
        fun from(t: ScanTask) =
            ScanResponse(t.id!!, t.projectId, t.engine, t.ref, t.status.name, t.findingCount, t.errorMessage)
    }
}

data class FindingResponse(
    val id: Long,
    val ruleCode: String,
    val filePath: String,
    val lineNumber: Int?,
    val severity: String,
    val message: String?,
    val status: String,
) {
    companion object {
        fun from(f: Finding) = FindingResponse(
            f.id!!, f.ruleCode, f.filePath, f.lineNumber, f.severity, f.message, f.status.name,
        )
    }
}

data class ItemResultResponse(
    val itemCode: String,
    val result: String,
    val findingCount: Int,
    val matchedFindingIds: List<Long>,
) {
    companion object {
        private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

        fun from(r: ChecklistItemResult): ItemResultResponse {
            val ids: List<Long> = r.matchedFindingIds
                ?.let { json -> runCatching { mapper.readValue(json, Array<Long>::class.java) }.getOrNull() }
                ?.toList() ?: emptyList()
            return ItemResultResponse(r.itemCode, r.result, r.findingCount, ids)
        }
    }
}

data class ComplianceResultResponse(
    val scanTaskId: Long,
    val projectId: Long,
    val evaluationId: Long?,
    val score: BigDecimal?,
    val totalItems: Int,
    val passed: Int,
    val failed: Int,
    val warning: Int,
    val manual: Int,
    val skipped: Int,
    val items: List<ItemResultResponse>,
) {
    companion object {
        fun from(scanTask: ScanTask, evaluation: ComplianceEvaluation?, items: List<ChecklistItemResult>) =
            ComplianceResultResponse(
                scanTaskId = scanTask.id!!,
                projectId = scanTask.projectId,
                evaluationId = evaluation?.id,
                score = evaluation?.score,
                totalItems = evaluation?.totalItems ?: 0,
                passed = evaluation?.passed ?: 0,
                failed = evaluation?.failed ?: 0,
                warning = evaluation?.warning ?: 0,
                manual = evaluation?.manual ?: 0,
                skipped = evaluation?.skipped ?: 0,
                items = items.map { ItemResultResponse.from(it) },
            )
    }
}
