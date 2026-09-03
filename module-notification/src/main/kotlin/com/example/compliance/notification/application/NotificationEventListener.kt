package com.example.compliance.notification.application

import com.example.compliance.common.event.FindingRegressionEvent
import com.example.compliance.common.event.RemediationWaiverEvent
import com.example.compliance.notification.domain.Channel
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/** 通知事件消费（spec §6.4 best-effort：失败仅日志，不影响发布方主流程）。零跨模块依赖（事件类在 common）。 */
@Component
class NotificationEventListener(private val sender: NotificationSender) {
    private val log = LoggerFactory.getLogger(NotificationEventListener::class.java)

    @EventListener
    fun onRegression(e: FindingRegressionEvent) {
        runCatching {
            sender.send(Channel.IN_APP, "finding regressed", "回归：${e.findingIds.size} 个 finding 在扫描 ${e.scanTaskId} 复现", emptyList())
        }.onFailure { log.warn("regression notification failed: project={} scan={}", e.projectId, e.scanTaskId, it) }
    }

    @EventListener
    fun onWaiver(e: RemediationWaiverEvent) {
        runCatching {
            sender.send(Channel.IN_APP, "finding waived", "finding ${e.findingId} 被豁免（${e.reason}）", listOf(e.actorId))
        }.onFailure { log.warn("waiver notification failed: project={} finding={}", e.projectId, e.findingId, it) }
    }
}
