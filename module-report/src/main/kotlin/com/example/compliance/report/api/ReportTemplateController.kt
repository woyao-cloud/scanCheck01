package com.example.compliance.report.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.report.api.dto.DraftRequest
import com.example.compliance.report.api.dto.TemplateVersionView
import com.example.compliance.report.application.ReportTemplateService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

/** 报告模板管理（仅 ADMIN/COMPLIANCE_MANAGER，SecurityConfig 路径门控）。 */
@RestController
@RequestMapping("/api/v1/reports")
class ReportTemplateController(private val service: ReportTemplateService) {

    @PostMapping("/templates/{type}/draft")
    fun draft(@PathVariable type: String, @Valid @RequestBody req: DraftRequest): ApiResponse<TemplateVersionView> =
        ApiResponse.ok(TemplateVersionView.from(service.draft(type, req.name, req.sections!!)))

    @PostMapping("/templates/{type}/publish")
    fun publish(@PathVariable type: String): ApiResponse<TemplateVersionView> =
        ApiResponse.ok(TemplateVersionView.from(service.publish(type)))

    @PostMapping("/templates/{type}/disable")
    fun disable(@PathVariable type: String): ApiResponse<TemplateVersionView> =
        ApiResponse.ok(TemplateVersionView.from(service.disable(type)))

    @GetMapping("/templates/{type}/versions")
    fun versions(@PathVariable type: String): ApiResponse<List<TemplateVersionView>> =
        ApiResponse.ok(service.versions(type).map { TemplateVersionView.from(it) })
}
