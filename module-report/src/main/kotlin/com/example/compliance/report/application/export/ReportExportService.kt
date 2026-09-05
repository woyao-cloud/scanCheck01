package com.example.compliance.report.application.export

import com.example.compliance.common.audit.AuditService
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.report.domain.ReportSnapshot
import com.example.compliance.report.infrastructure.ReportSnapshotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 二进制导出产物：filename（Content-Disposition 用）+ bytes（spec R-M16-P1）。 */
data class ExportArtifact(val filename: String, val bytes: ByteArray)

/** 快照 → xlsx/pdf 二进制导出（spec R-M16-D2/D3/D4）：只读载入不可变快照，纯渲染，导出动作写审计 REPORT_EXPORT。 */
@Service
class ReportExportService(
    private val snapshotRepository: ReportSnapshotRepository,
    private val auditService: AuditService,
) {

    @Transactional(readOnly = true)
    fun exportXlsx(id: Long, actorId: Long?): ExportArtifact {
        val snapshot = snapshot(id)
        val bytes = XlsxRenderer.render(ReportExportModel.sheetsFor(snapshot.snapshotType, snapshot.payload))
        recordAudit(snapshot, "xlsx", actorId)
        return ExportArtifact(filename(snapshot, "xlsx"), bytes)
    }

    @Transactional(readOnly = true)
    fun exportPdf(id: Long, actorId: Long?): ExportArtifact {
        val snapshot = snapshot(id)
        val bytes = PdfRenderer.render(
            title = "Report #${snapshot.id} (${snapshot.snapshotType})",
            meta = "template v${snapshot.templateVersionNo} · generatedAt ${snapshot.generatedAt}",
            sheets = ReportExportModel.sheetsFor(snapshot.snapshotType, snapshot.payload),
        )
        recordAudit(snapshot, "pdf", actorId)
        return ExportArtifact(filename(snapshot, "pdf"), bytes)
    }

    private fun snapshot(id: Long): ReportSnapshot = snapshotRepository.findById(id)
        .orElseThrow { BusinessException(404, "report snapshot not found: $id") }

    private fun filename(snapshot: ReportSnapshot, ext: String) =
        "report-${snapshot.id}-${snapshot.snapshotType.lowercase()}.$ext"

    private fun recordAudit(snapshot: ReportSnapshot, format: String, actorId: Long?) {
        // Ruling #34 先例：audit_log.detail 是 JSONB，detail 必须传合法 JSON
        auditService.record(
            action = "REPORT_EXPORT",
            module = "report",
            resourceType = "report_snapshot",
            resourceId = snapshot.id,
            userId = actorId,
            detail = """{"format":"$format","snapshotType":"${snapshot.snapshotType}"}""",
        )
    }
}
