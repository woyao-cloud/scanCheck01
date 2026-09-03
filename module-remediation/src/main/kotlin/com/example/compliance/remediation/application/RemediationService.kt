package com.example.compliance.remediation.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.remediation.domain.RemediationTask
import com.example.compliance.remediation.infrastructure.RemediationTaskRepository
import com.example.compliance.result.application.FindingLifecyclePort
import com.example.compliance.result.application.FindingView
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.scan.application.ScanTriggerPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** 整改闭环服务：经 FindingLifecyclePort 驱动 finding 生命周期（P2-D4/D5），task.status 仅镜像。 */
@Service
class RemediationService(
    private val taskRepository: RemediationTaskRepository,
    private val lifecyclePort: FindingLifecyclePort,
    private val triggerPort: ScanTriggerPort,
) {
    /** 派单：创建整改任务并把 finding 置为 ASSIGNED（task.status 镜像写入）。 */
    @Transactional
    fun assign(
        findingId: Long, actorId: Long, assigneeUserId: Long?, plan: String?, dueDate: LocalDate?,
    ): FindingRemediationView {
        val finding = mustGetFinding(findingId)
        if (finding.status != FindingStatus.CONFIRMED) {
            throw BusinessException(409, "finding not in CONFIRMED state: $findingId")
        }
        val existing = taskRepository.findByFindingId(findingId)
        val task = existing ?: taskRepository.save(RemediationTask().apply {
            this.findingId = findingId
            this.projectId = finding.projectId
            this.createdBy = actorId
        })
        if (existing == null) {
            task.assigneeUserId = assigneeUserId
            task.plan = plan
            task.dueDate = dueDate
        }
        val newStatus = lifecyclePort.transition(findingId, FindingStatus.ASSIGNED, "assigned", actorId)
        task.status = newStatus   // 镜像：task.status 跟随权威 transition 的返回
        // 响应 finding 也回显权威状态（P2-D4 镜像：finding.status == task.status），避免返回派单前旧视图
        return FindingRemediationView(finding.copy(status = newStatus), taskRepository.save(task).toView())
    }

    @Transactional(readOnly = true)
    fun get(findingId: Long): FindingRemediationView {
        val finding = mustGetFinding(findingId)
        return FindingRemediationView(finding, taskRepository.findByFindingId(findingId)?.toView())
    }

    @Transactional(readOnly = true)
    fun listByProject(projectId: Long): List<FindingRemediationView> {
        val tasks = taskRepository.findByProjectId(projectId).associateBy { it.findingId }
        return tasks.values.map { task ->
            FindingRemediationView(
                lifecyclePort.findById(task.findingId) ?: return@map null,
                task.toView(),
            )
        }.filterNotNull()
    }

    /** 人工确认问题真实存在：NEW → CONFIRMED。 */
    @Transactional
    fun confirm(findingId: Long, actorId: Long): FindingRemediationView {
        val finding = mustGetFinding(findingId)
        if (finding.status != FindingStatus.NEW) {
            throw BusinessException(409, "finding not in NEW state: $findingId")
        }
        lifecyclePort.transition(findingId, FindingStatus.CONFIRMED, "confirmed", actorId)
        return get(findingId)
    }

    /** 开始整改：ASSIGNED → FIXING。 */
    @Transactional
    fun startFix(findingId: Long, actorId: Long): FindingRemediationView {
        val finding = mustGetFinding(findingId)
        if (finding.status != FindingStatus.ASSIGNED) {
            throw BusinessException(409, "finding not in ASSIGNED state: $findingId")
        }
        return mirrorTransition(findingId, FindingStatus.FIXING, "fix_started", actorId)
    }

    /** 标记修复：FIXING → FIXED，必附 evidence。
     *  F4 (final review I6): 服务端校验受让人 —— spec §6.1 任意已登录用户可调端点，但只有
     *  受让人（或 ADMIN 覆写）可把该 finding 标记为已修复。未派单/受让人为空时不校验（保持原行为）。 */
    @Transactional
    fun markFixed(findingId: Long, actorId: Long, actorAuthorities: Set<String>, evidenceType: String, evidenceRef: String): FindingRemediationView {
        val finding = mustGetFinding(findingId)
        if (evidenceType.isBlank() || evidenceRef.isBlank()) {
            throw BusinessException(400, "evidence required for fixed")
        }
        if (finding.status != FindingStatus.FIXING) {
            throw BusinessException(409, "finding not in FIXING state: $findingId")
        }
        val assignee = taskRepository.findByFindingId(findingId)?.assigneeUserId
        if (assignee != null && actorId != assignee && "ROLE_ADMIN" !in actorAuthorities) {
            throw BusinessException(403, "only the assignee can mark fixed")
        }
        lifecyclePort.addEvidence(findingId, evidenceType, evidenceRef, actorId)
        return mirrorTransition(findingId, FindingStatus.FIXED, "fixed", actorId)
    }

    /** 追加证据（无转移）。 */
    @Transactional
    fun addEvidence(findingId: Long, actorId: Long, evidenceType: String, evidenceRef: String): FindingRemediationView {
        mustGetFinding(findingId)
        if (evidenceType.isBlank() || evidenceRef.isBlank()) {
            throw BusinessException(400, "evidence required")
        }
        lifecyclePort.addEvidence(findingId, evidenceType, evidenceRef, actorId)
        return get(findingId)
    }

    /** GET /findings：按项目/状态/严重级过滤 + 分页（内存分页，spec §4.4）。 */
    @Transactional(readOnly = true)
    fun list(projectId: Long?, status: FindingStatus?, severity: String?, page: Int, size: Int): List<FindingRemediationView> {
        val findings = lifecyclePort.findingsByProject(projectId ?: 0L, status)
            .filter { severity == null || it.severity.equals(severity, ignoreCase = true) }
        val tasks = taskRepository.findByProjectId(projectId ?: 0L).associateBy { it.findingId }
        val views = findings.map { f -> FindingRemediationView(f, tasks[f.id]?.toView()) }
        val from = (page.coerceAtLeast(0)) * size.coerceAtLeast(1)
        return if (from >= views.size) emptyList() else views.subList(from, minOf(from + size, views.size))
    }

    /** 终态转移：IGNORED/FALSE_POSITIVE/ACCEPTED_RISK/WAIVED（必附 reason + evidence）。 */
    @Transactional
    fun status(
        findingId: Long, to: FindingStatus, reason: String, evidenceType: String, evidenceRef: String, actorId: Long,
    ): FindingRemediationView {
        if (to !in TERMINAL_STATES) {
            throw BusinessException(400, "target status not terminal: $to")
        }
        if (reason.isBlank() || evidenceType.isBlank() || evidenceRef.isBlank()) {
            throw BusinessException(400, "reason and evidence required for terminal status")
        }
        mustGetFinding(findingId)
        lifecyclePort.addEvidence(findingId, evidenceType, evidenceRef, actorId)
        return mirrorTransition(findingId, to, reason, actorId)
    }

    /** 请求复扫验证：FIXED → RECHECKING，并创建复扫 ScanTask（trigger_type=MANUAL）。
     *  spec §4.3：reason 记入 finding_status；复扫完成后由编排器 verifyRechecking 闭环。
     *  Ruling #45：刻意不加 @Transactional —— triggerScan 创建的复扫 PENDING 行必须自提交后才
     *  派发 executeAsync；若加入外层事务，@Async 线程可能先于提交 findById → 复扫任务永久卡死
     *  PENDING。transition 与 taskRepository.save 各自自带事务自提交，状态语义不受影响。 */
    fun requestRecheck(findingId: Long, actorId: Long): FindingRemediationView {
        val finding = mustGetFinding(findingId)
        if (finding.status != FindingStatus.FIXED) {
            throw BusinessException(409, "finding not in FIXED state: $findingId")
        }
        val scan = triggerPort.triggerScan(
            projectId = finding.projectId, engine = finding.engine, ref = null,
            triggerType = "MANUAL", requestId = "recheck-f$findingId",
        )
        return mirrorTransition(findingId, FindingStatus.RECHECKING, "recheck_requested:scan_${scan.id}", actorId)
    }

    companion object {
        /** 终态集（spec §4.2）：到达后仅复现/复审系统动作可离开。 */
        val TERMINAL_STATES = setOf(
            FindingStatus.IGNORED, FindingStatus.FALSE_POSITIVE,
            FindingStatus.ACCEPTED_RISK, FindingStatus.WAIVED,
        )
    }

    /** 状态转移 + task.status 镜像（P2-D4）。 */
    private fun mirrorTransition(findingId: Long, to: FindingStatus, reason: String, actorId: Long): FindingRemediationView {
        val status = lifecyclePort.transition(findingId, to, reason, actorId)
        val task = taskRepository.findByFindingId(findingId)
        if (task != null) {
            task.status = status
            taskRepository.save(task)
        }
        return get(findingId)
    }

    protected fun mustGetFinding(findingId: Long): FindingView =
        lifecyclePort.findById(findingId)
            ?: throw BusinessException(404, "finding not found: $findingId")

    private fun RemediationTask.toView() = RemediationTaskView(
        id!!, findingId, projectId, assigneeUserId, createdBy, plan, dueDate, status, createdAt!!,
    )
}
