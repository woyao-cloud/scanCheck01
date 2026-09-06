package com.example.compliance.notification.application

import com.example.compliance.common.event.FindingRegressionEvent
import com.example.compliance.common.event.RemediationAssignedEvent
import com.example.compliance.common.event.RemediationCompletedEvent
import com.example.compliance.common.event.RemediationWaiverEvent
import com.example.compliance.common.event.ReportSnapshotGeneratedEvent
import com.example.compliance.common.event.ScanCompletedEvent
import com.example.compliance.project.infrastructure.ProjectRepository
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/** 通知事件消费（spec §6.4 best-effort：失败仅日志，不影响发布方主流程）。
 *  收件人解析第一层（R-M17-D2）：事件 → 收件人 userIds（owner/assignee/actor）；
 *  NotificationService.notify 做 userId → email + 渠道 fan-out。
 *  回归收件人修复（spec §3.4）：emptyList() → project.ownerUserId。 */
@Component
class NotificationEventListener(
    private val notificationService: NotificationService,
    private val projectRepository: ProjectRepository,
) {
    private val log = LoggerFactory.getLogger(NotificationEventListener::class.java)

    @EventListener
    fun onRegression(e: FindingRegressionEvent) = safe("regression project=${e.projectId}") {
        ownerRecipient(e.projectId)?.let { owner ->
            notificationService.notify(
                "FINDING_REGRESSION", "finding regressed",
                "回归：${e.findingIds.size} 个 finding 在扫描 ${e.scanTaskId} 复现", listOf(owner),
            )
        }
    }

    @EventListener
    fun onWaiver(e: RemediationWaiverEvent) = safe("waiver project=${e.projectId}") {
        notificationService.notify(
            "REMEDIATION_WAIVER", "finding waived",
            "finding ${e.findingId} 被豁免（${e.reason}）", listOf(e.actorId),
        )
    }

    @EventListener
    fun onScanCompleted(e: ScanCompletedEvent) = safe("scan project=${e.projectId}") {
        ownerRecipient(e.projectId)?.let { owner ->
            notificationService.notify("SCAN_COMPLETED", "scan completed", "扫描 ${e.scanTaskId} 完成：${e.status}", listOf(owner))
        }
    }

    @EventListener
    fun onReportSnapshotGenerated(e: ReportSnapshotGeneratedEvent) = safe("report snapshot project=${e.projectId}") {
        e.projectId?.let { projectId -> ownerRecipient(projectId) }?.let { owner ->
            notificationService.notify(
                "REPORT_SNAPSHOT_GENERATED", "report snapshot generated",
                "快照 ${e.snapshotId} 已生成（${e.snapshotType}）", listOf(owner),
            )
        }
    }

    @EventListener
    fun onRemediationAssigned(e: RemediationAssignedEvent) = safe("remediation assigned finding=${e.findingId}") {
        notificationService.notify("REMEDIATION_ASSIGNED", "remediation assigned", "finding ${e.findingId} 已指派给你", listOf(e.assigneeId))
    }

    @EventListener
    fun onRemediationCompleted(e: RemediationCompletedEvent) = safe("remediation completed finding=${e.findingId}") {
        notificationService.notify(
            "REMEDIATION_COMPLETED", "remediation completed",
            "finding ${e.findingId} 已标记完成", listOfNotNull(e.actorId, e.assigneeId).distinct(),
        )
    }

    /** 项目负责人收件人：项目缺失或 owner 为空 → null（不通知）。 */
    private fun ownerRecipient(projectId: Long): Long? =
        projectRepository.findById(projectId).orElse(null)?.ownerUserId

    private fun safe(logCtx: String, block: () -> Unit) {
        runCatching(block).onFailure { log.warn("notification failed: {}", logCtx, it) }
    }
}
