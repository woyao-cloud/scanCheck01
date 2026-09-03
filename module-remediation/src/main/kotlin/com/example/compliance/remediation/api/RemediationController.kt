package com.example.compliance.remediation.api

import com.example.compliance.remediation.application.FindingRemediationView
import com.example.compliance.remediation.application.RemediationService
import com.example.compliance.result.domain.FindingStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

/** 整改闭环 finding 中心端点（spec §4.4）。 */
@RestController
@RequestMapping("/api/v1/remediation")
class RemediationController(private val service: RemediationService) {

    data class AssignCommand(val assigneeId: Long?, val plan: String?, val dueDate: LocalDate?)
    data class EvidenceCommand(val evidenceType: String, val evidenceRef: String)

    @GetMapping("/findings")
    fun list(
        @RequestParam(required = false) projectId: Long?,
        @RequestParam(required = false) status: FindingStatus?,
        @RequestParam(required = false) severity: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): List<FindingRemediationView> = service.list(projectId, status, severity, page, size)

    @PostMapping("/findings/{id}/confirm")
    @PreAuthorize("hasAnyRole('COMPLIANCE_MANAGER','PROJECT_OWNER')")
    fun confirm(@PathVariable id: Long, auth: Authentication?): FindingRemediationView =
        service.confirm(id, actorId(auth))

    @PostMapping("/findings/{id}/assign")
    @PreAuthorize("hasAnyRole('COMPLIANCE_MANAGER','PROJECT_OWNER')")
    fun assign(@PathVariable id: Long, @RequestBody cmd: AssignCommand, auth: Authentication?): FindingRemediationView =
        service.assign(id, actorId(auth), cmd.assigneeId, cmd.plan, cmd.dueDate)

    @PostMapping("/findings/{id}/fixing")
    @PreAuthorize("hasAnyRole('COMPLIANCE_MANAGER','PROJECT_OWNER','DEVELOPER')")
    fun fixing(@PathVariable id: Long, auth: Authentication?): FindingRemediationView =
        service.startFix(id, actorId(auth))

    @PostMapping("/findings/{id}/fixed")
    @PreAuthorize("isAuthenticated()")
    fun fixed(@PathVariable id: Long, @RequestBody cmd: EvidenceCommand, auth: Authentication?): FindingRemediationView =
        service.markFixed(id, actorId(auth), cmd.evidenceType, cmd.evidenceRef)

    @PostMapping("/findings/{id}/evidence")
    @PreAuthorize("isAuthenticated()")
    fun evidence(@PathVariable id: Long, @RequestBody cmd: EvidenceCommand, auth: Authentication?): FindingRemediationView =
        service.addEvidence(id, actorId(auth), cmd.evidenceType, cmd.evidenceRef)

    @PostMapping("/findings/{id}/recheck")
    @PreAuthorize("hasAnyRole('COMPLIANCE_MANAGER','PROJECT_OWNER')")
    fun recheck(@PathVariable id: Long, auth: Authentication?): FindingRemediationView =
        service.requestRecheck(id, actorId(auth))

    data class StatusCommand(
        val status: FindingStatus,
        val reason: String,
        val evidenceType: String?,
        val evidenceRef: String?,
    )

    @PutMapping("/findings/{id}/status")
    @PreAuthorize("hasRole('COMPLIANCE_MANAGER')")
    fun status(@PathVariable id: Long, @RequestBody cmd: StatusCommand, auth: Authentication?): FindingRemediationView =
        service.status(id, cmd.status, cmd.reason, cmd.evidenceType ?: "", cmd.evidenceRef ?: "", actorId(auth))

    private fun actorId(@Suppress("UNUSED_PARAMETER") auth: Authentication?): Long {
        // module-common 目前无 AuthPrincipal 类型，占位返回 1L（系统操作者）；
        // M9 RBAC 接入时在此解析 principal 的真实用户身份。
        return 1L
    }
}
