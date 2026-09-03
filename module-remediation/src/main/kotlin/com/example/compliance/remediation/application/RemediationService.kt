package com.example.compliance.remediation.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.remediation.domain.RemediationTask
import com.example.compliance.remediation.infrastructure.RemediationTaskRepository
import com.example.compliance.result.application.FindingLifecyclePort
import com.example.compliance.result.application.FindingView
import com.example.compliance.result.domain.FindingStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** 整改闭环服务：经 FindingLifecyclePort 驱动 finding 生命周期（P2-D4/D5），task.status 仅镜像。 */
@Service
class RemediationService(
    private val taskRepository: RemediationTaskRepository,
    private val lifecyclePort: FindingLifecyclePort,
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

    protected fun mustGetFinding(findingId: Long): FindingView =
        lifecyclePort.findById(findingId)
            ?: throw BusinessException(404, "finding not found: $findingId")

    private fun RemediationTask.toView() = RemediationTaskView(
        id!!, findingId, projectId, assigneeUserId, createdBy, plan, dueDate, status, createdAt!!,
    )
}
