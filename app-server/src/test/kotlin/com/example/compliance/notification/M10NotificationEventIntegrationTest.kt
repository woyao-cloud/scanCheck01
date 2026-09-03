package com.example.compliance.notification

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.common.event.FindingRegressionEvent
import com.example.compliance.common.event.RemediationWaiverEvent
import com.example.compliance.notification.infrastructure.NotificationRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.assertTrue

/** M10 I4 通知事件端到端（spec §6.4）：publishEvent → NotificationEventListener → NotificationSender → 落库。
 *  数据前缀 NTF-*（共享容器，全局唯一）。best-effort 失败注入在单测层（NotificationEventListenerTest），
 *  此处覆盖真实发布→落库闭环。 */
class M10NotificationEventIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var publisher: ApplicationEventPublisher
    @Autowired lateinit var notificationRepository: NotificationRepository

    @Test
    fun `waiver event is persisted as IN_APP notification for the actor`() {
        publisher.publishEvent(RemediationWaiverEvent(projectId = 9901L, findingId = 1L, actorId = 7L, reason = "业务豁免"))
        val rows = notificationRepository.findByRecipient("7")
        assertTrue(rows.any { it.type == "EVENT" && it.status == "SENT" && it.title == "finding waived" })
    }

    @Test
    fun `regression event with no recipients persists nothing`() {
        publisher.publishEvent(FindingRegressionEvent(projectId = 9902L, scanTaskId = 8801L, findingIds = listOf(2L, 3L)))
        // recipients 为空 → 零落库行（仅日志占位，不抛）—— 显式断言而非空跑
        assertTrue(notificationRepository.findByStatusAndChannel("SENT", "IN_APP").none { it.title == "finding regressed" })
    }
}
