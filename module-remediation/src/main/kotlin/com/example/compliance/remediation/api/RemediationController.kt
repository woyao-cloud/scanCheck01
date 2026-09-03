package com.example.compliance.remediation.api

import com.example.compliance.common.auth.AuthPrincipal
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
    // F3 (final review I5): ADMIN 全 ✓（spec §6.1 矩阵）
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_MANAGER','PROJECT_OWNER')")
    fun confirm(@PathVariable id: Long, auth: Authentication?): FindingRemediationView =
        service.confirm(id, actorId(auth))

    @PostMapping("/findings/{id}/assign")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_MANAGER','PROJECT_OWNER')")
    fun assign(@PathVariable id: Long, @RequestBody cmd: AssignCommand, auth: Authentication?): FindingRemediationView =
        service.assign(id, actorId(auth), cmd.assigneeId, cmd.plan, cmd.dueDate)

    @PostMapping("/findings/{id}/fixing")
    // F3 (final review m9): spec §4.2 ASSIGNED→FIXING = 受让人(DEVELOPER) + PROJECT_OWNER —— 去掉 COMPLIANCE_MANAGER 超授权
    @PreAuthorize("hasAnyRole('ADMIN','PROJECT_OWNER','DEVELOPER')")
    fun fixing(@PathVariable id: Long, auth: Authentication?): FindingRemediationView =
        service.startFix(id, actorId(auth))

    @PostMapping("/findings/{id}/fixed")
    // F3/F4 (spec §6.1): 任意已登录用户可调；服务端校验受让人（见 markFixed）
    @PreAuthorize("isAuthenticated()")
    fun fixed(@PathVariable id: Long, @RequestBody cmd: EvidenceCommand, auth: Authentication?): FindingRemediationView =
        service.markFixed(id, actorId(auth), isAdmin(auth), cmd.evidenceType, cmd.evidenceRef)

    @PostMapping("/findings/{id}/evidence")
    @PreAuthorize("isAuthenticated()")
    fun evidence(@PathVariable id: Long, @RequestBody cmd: EvidenceCommand, auth: Authentication?): FindingRemediationView =
        service.addEvidence(id, actorId(auth), cmd.evidenceType, cmd.evidenceRef)

    @PostMapping("/findings/{id}/recheck")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_MANAGER','PROJECT_OWNER')")
    fun recheck(@PathVariable id: Long, auth: Authentication?): FindingRemediationView =
        service.requestRecheck(id, actorId(auth))

    data class StatusCommand(
        val status: FindingStatus,
        val reason: String,
        val evidenceType: String?,
        val evidenceRef: String?,
    )

    @PutMapping("/findings/{id}/status")
    // F3 (final review I5): status 仅 ADMIN / COMPLIANCE_MANAGER（终态转移是高权限操作）
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_MANAGER')")
    fun status(@PathVariable id: Long, @RequestBody cmd: StatusCommand, auth: Authentication?): FindingRemediationView =
        service.status(id, cmd.status, cmd.reason, cmd.evidenceType ?: "", cmd.evidenceRef ?: "", actorId(auth))

    /** 真实用户 id（F4, final review I6）：AuthPrincipal 解析；@WithMockUser 等 String principal 回落 1L（系统/测试操作者）。 */
    private fun actorId(auth: Authentication?): Long =
        (auth?.principal as? AuthPrincipal)?.userId ?: 1L

    /** M10 清理②：hasRole 接线 —— ADMIN 覆写判定经 AuthPrincipal.hasRole（原 actorAuthorities 直查 authorities 死代码化）。 */
    private fun isAdmin(auth: Authentication?): Boolean =
        (auth?.principal as? AuthPrincipal)?.hasRole("ADMIN") ?: false
}
