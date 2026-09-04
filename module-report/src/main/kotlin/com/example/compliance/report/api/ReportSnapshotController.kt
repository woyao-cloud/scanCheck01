package com.example.compliance.report.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.common.api.PageResponse
import com.example.compliance.common.auth.AuthPrincipal
import com.example.compliance.report.api.dto.GenerateRequest
import com.example.compliance.report.api.dto.SnapshotSummaryView
import com.example.compliance.report.api.dto.SnapshotView
import com.example.compliance.report.application.ReportGenerationService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

/** 报告快照生成/查询/导出（spec §3.3；认证用户即可，RBAC 见 SecurityConfig）。 */
@RestController
@RequestMapping("/api/v1/reports")
class ReportSnapshotController(private val generationService: ReportGenerationService) {

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
    fun export(@PathVariable id: Long, @RequestParam(defaultValue = "json") format: String): ApiResponse<Any> =
        ApiResponse.ok(generationService.export(id, format))
}
