package com.example.compliance.report.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.report.application.ComplianceSummary
import com.example.compliance.report.application.ReportService
import com.example.compliance.report.application.ScanSummary
import com.example.compliance.report.application.TrendPoint
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/reports")
class ReportController(private val reportService: ReportService) {

    @GetMapping("/scan-summary")
    fun scanSummary(@RequestParam taskId: Long): ApiResponse<ScanSummary> =
        ApiResponse.ok(reportService.scanSummary(taskId))

    @GetMapping("/compliance-summary")
    fun complianceSummary(@RequestParam projectId: Long): ApiResponse<ComplianceSummary> =
        ApiResponse.ok(reportService.complianceSummary(projectId))

    @GetMapping("/trend")
    fun trend(
        @RequestParam projectId: Long,
        @RequestParam(defaultValue = "30") days: Int,
    ): ApiResponse<List<TrendPoint>> = ApiResponse.ok(reportService.trend(projectId, days))
}
