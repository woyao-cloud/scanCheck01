package com.example.compliance.admin.api

import com.example.compliance.admin.api.AuditLogView
import com.example.compliance.common.api.ApiResponse
import com.example.compliance.common.api.PageView
import com.example.compliance.common.audit.AuditLogFilter
import com.example.compliance.common.audit.AuditQueryService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.Instant

/** 审计日志查询（spec R-M16-D5/D6/D7）：/api/v1/audit-logs 避开 /admin/ 前缀路径守卫使 AUDITOR 可达，
 *  方法级 @PreAuthorize(ADMIN,AUDITOR) 双保险（SecurityConfig 无此路径规则 → 认证即可 + 方法门控）。 */
@RestController
@RequestMapping("/api/v1/audit-logs")
class AuditLogController(private val query: AuditQueryService) {

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    fun list(
        @RequestParam(required = false) module: String?,
        @RequestParam(required = false) action: String?,
        @RequestParam(required = false) userId: Long?,
        @RequestParam(required = false) resourceType: String?,
        @RequestParam(required = false) resourceId: Long?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageView<AuditLogView>> {
        val filter = AuditLogFilter(module, action, userId, resourceType, resourceId, from, to)
        val result = query.search(filter, page, size)
        return ApiResponse.ok(
            PageView(
                items = result.content.map { AuditLogView.from(it) },
                page = result.number,
                size = result.size,
                total = result.totalElements,
            )
        )
    }
}
