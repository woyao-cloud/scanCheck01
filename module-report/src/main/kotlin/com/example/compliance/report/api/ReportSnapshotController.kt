package com.example.compliance.report.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.common.api.PageResponse
import com.example.compliance.common.auth.AuthPrincipal
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.report.api.dto.GenerateRequest
import com.example.compliance.report.api.dto.SnapshotSummaryView
import com.example.compliance.report.api.dto.SnapshotView
import com.example.compliance.report.application.ReportGenerationService
import com.example.compliance.report.application.export.ExportArtifact
import com.example.compliance.report.application.export.ReportExportService
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

/** 报告快照生成/查询/导出（spec §3.3；认证用户即可，RBAC 见 SecurityConfig）。
 *  json/html 走 {data:...} 封装；xlsx/pdf 走二进制附件（spec R-M16-D3）。 */
@RestController
@RequestMapping("/api/v1/reports")
class ReportSnapshotController(
    private val generationService: ReportGenerationService,
    private val exportService: ReportExportService,
) {

    @PostMapping("/{type}/generate")
    fun generate(
        @PathVariable type: String,
        @RequestBody(required = false) req: GenerateRequest?,
        authentication: Authentication?,
    ): ApiResponse<SnapshotView> {
        val actorId = (authentication?.principal as? AuthPrincipal)?.userId ?: 1L
        val snapshot = generationService.generate(type, req?.projectId, req?.scanTaskId, actorId)
        return ApiResponse.ok(SnapshotView.from(snapshot))
    }

    @GetMapping("/snapshots")
    fun list(
        @RequestParam(required = false) projectId: Long?,
        @RequestParam(required = false) type: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<SnapshotSummaryView>> {
        val result = generationService.list(projectId, type, page, size)
        return ApiResponse.ok(
            PageResponse(
                items = result.content.map { SnapshotSummaryView.from(it) },
                page = result.number,
                size = result.size,
                total = result.totalElements,
            )
        )
    }

    @GetMapping("/snapshots/{id}")
    fun detail(@PathVariable id: Long): ApiResponse<SnapshotView> =
        ApiResponse.ok(SnapshotView.from(generationService.detail(id)))

    @GetMapping("/snapshots/{id}/export")
    fun export(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "json") format: String,
        authentication: Authentication?,
    ): ResponseEntity<Any> {
        val actorId = (authentication?.principal as? AuthPrincipal)?.userId ?: 1L
        return when (format) {
            "json", "html" -> ResponseEntity<Any>(ApiResponse.ok(generationService.export(id, format)), HttpStatus.OK)
            "xlsx" -> binary(exportService.exportXlsx(id, actorId), XLSX_MEDIA_TYPE)
            "pdf" -> binary(exportService.exportPdf(id, actorId), PDF_MEDIA_TYPE)
            else -> throw BusinessException(400, "unsupported export format: $format")
        }
    }

    private fun binary(artifact: ExportArtifact, mediaType: MediaType): ResponseEntity<Any> {
        val headers = HttpHeaders().apply {
            contentType = mediaType
            set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${artifact.filename}\"")
        }
        return ResponseEntity<Any>(ByteArrayResource(artifact.bytes), headers, HttpStatus.OK)
    }

    companion object {
        private val XLSX_MEDIA_TYPE = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        private val PDF_MEDIA_TYPE = MediaType.parseMediaType("application/pdf")
    }
}
