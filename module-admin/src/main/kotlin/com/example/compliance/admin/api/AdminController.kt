package com.example.compliance.admin.api

import com.example.compliance.admin.application.AdminDashboardView
import com.example.compliance.admin.application.AdminQueryService
import com.example.compliance.common.api.PageView
import com.example.compliance.result.application.FindingView
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.scan.application.ScanTaskView
import com.example.compliance.scan.domain.ScanTaskStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

/** 管理后台端点（spec §6.2，ADMIN-only —— SecurityConfig URL 守卫 + 方法注解双保险）。 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
class AdminController(private val query: AdminQueryService) {

    @GetMapping("/dashboard")
    fun dashboard(): AdminDashboardView = query.dashboard()

    @GetMapping("/scans")
    fun scans(
        @RequestParam(required = false) projectId: Long?,
        @RequestParam(required = false) engine: String?,
        @RequestParam(required = false) status: ScanTaskStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageView<ScanTaskView> = query.scans(projectId, engine, status, page, size)

    @GetMapping("/findings")
    fun findings(
        @RequestParam(required = false) projectId: Long?,
        @RequestParam(required = false) status: FindingStatus?,
        @RequestParam(required = false) severity: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageView<FindingView> = query.findings(projectId, status, severity, page, size)
}
