package com.example.compliance.scan.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.scan.api.dto.ComplianceResultResponse
import com.example.compliance.scan.api.dto.FindingResponse
import com.example.compliance.scan.api.dto.ScanRequest
import com.example.compliance.scan.api.dto.ScanResponse
import com.example.compliance.scan.application.ScanTaskService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
class ScanController(private val scanTaskService: ScanTaskService) {

    @PostMapping("/api/v1/projects/{projectId}/scan-tasks")
    fun start(@PathVariable projectId: Long, @Valid @RequestBody req: ScanRequest): ApiResponse<ScanResponse> =
        ApiResponse.ok(ScanResponse.from(scanTaskService.startScan(projectId, req.engine, req.ref)))

    @GetMapping("/api/v1/scan-tasks/{id}")
    fun get(@PathVariable id: Long): ApiResponse<ScanResponse> =
        ApiResponse.ok(ScanResponse.from(scanTaskService.get(id)))

    @PostMapping("/api/v1/scan-tasks/{id}/cancel")
    fun cancel(@PathVariable id: Long): ApiResponse<ScanResponse> =
        ApiResponse.ok(ScanResponse.from(scanTaskService.cancel(id)))

    @GetMapping("/api/v1/scan-tasks/{id}/findings")
    fun findings(@PathVariable id: Long): ApiResponse<List<FindingResponse>> =
        ApiResponse.ok(scanTaskService.findings(id).map { FindingResponse.from(it) })

    @GetMapping("/api/v1/scan-tasks/{id}/compliance-results")
    fun complianceResults(@PathVariable id: Long): ApiResponse<ComplianceResultResponse> {
        val view = scanTaskService.complianceResults(id)
        return ApiResponse.ok(ComplianceResultResponse.from(view.scanTask, view.evaluation, view.items))
    }
}
