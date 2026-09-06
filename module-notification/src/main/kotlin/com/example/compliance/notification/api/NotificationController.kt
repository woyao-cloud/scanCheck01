package com.example.compliance.notification.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.common.api.PageResponse
import com.example.compliance.common.auth.AuthPrincipal
import com.example.compliance.notification.application.NotificationService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

/** 站内信中心（spec §3.5）：当前用户自助（无 admin 全量视图）。全部按 AuthPrincipal.userId 过滤 IN_APP 行。
 *  方法级 @PreAuthorize 显式声明意图；路由落 SecurityConfig `anyRequest().authenticated()`（无 SecurityConfig 改动）。 */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(private val service: NotificationService) {

    @PreAuthorize("isAuthenticated()")
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "false") unreadOnly: Boolean,
        authentication: Authentication?,
    ): ApiResponse<PageResponse<NotificationView>> {
        val result = service.listMy(userId(authentication), page, size, unreadOnly)
        return ApiResponse.ok(
            PageResponse(
                items = result.content.map { NotificationView.from(it) },
                page = result.number,
                size = result.size,
                total = result.totalElements,
            )
        )
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/unread-count")
    fun unreadCount(authentication: Authentication?): ApiResponse<UnreadCount> =
        ApiResponse.ok(UnreadCount(service.unreadCount(userId(authentication))))

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{id}/read")
    fun read(@PathVariable id: Long, authentication: Authentication?): ApiResponse<Unit> {
        service.markRead(id, userId(authentication))
        return ApiResponse.ok()
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/read-all")
    fun readAll(authentication: Authentication?): ApiResponse<UnreadCount> =
        ApiResponse.ok(UnreadCount(service.markReadAll(userId(authentication)).toLong()))

    /** 真实用户 id（镜像 ReportSnapshotController/RemediationController）：AuthPrincipal 解析，非 AuthPrincipal 回落 1L。 */
    private fun userId(authentication: Authentication?): Long =
        (authentication?.principal as? AuthPrincipal)?.userId ?: 1L
}
