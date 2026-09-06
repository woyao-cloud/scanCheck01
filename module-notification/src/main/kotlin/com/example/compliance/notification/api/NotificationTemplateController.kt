package com.example.compliance.notification.api

import com.example.compliance.common.api.ApiResponse
import com.example.compliance.notification.application.NotificationTemplateService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 通知模板管理（仅 ADMIN/COMPLIANCE_MANAGER，方法级 @PreAuthorize —— R-M18-10，无 SecurityConfig 改动）。 */
@RestController
@RequestMapping("/api/v1/notification-templates")
class NotificationTemplateController(private val service: NotificationTemplateService) {

    @PostMapping("/{type}/draft")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_MANAGER')")
    fun draft(@PathVariable type: String, @Valid @RequestBody req: DraftRequest): ApiResponse<TemplateVersionView> =
        ApiResponse.ok(TemplateVersionView.from(service.draft(type, req.name, req.content!!)))

    @PostMapping("/{type}/publish")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_MANAGER')")
    fun publish(@PathVariable type: String): ApiResponse<TemplateVersionView> =
        ApiResponse.ok(TemplateVersionView.from(service.publish(type)))

    @PostMapping("/{type}/disable")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_MANAGER')")
    fun disable(@PathVariable type: String): ApiResponse<TemplateVersionView> =
        ApiResponse.ok(TemplateVersionView.from(service.disable(type)))

    @GetMapping("/{type}/versions")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_MANAGER')")
    fun versions(@PathVariable type: String): ApiResponse<List<TemplateVersionView>> =
        ApiResponse.ok(service.versions(type).map { TemplateVersionView.from(it) })
}
