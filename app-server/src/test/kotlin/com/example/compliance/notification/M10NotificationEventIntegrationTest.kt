package com.example.compliance.notification

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.common.event.FindingRegressionEvent
import com.example.compliance.common.event.RemediationWaiverEvent
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.example.compliance.project.domain.Project
import com.example.compliance.project.infrastructure.ProjectRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.assertTrue

/** M10 I4 通知事件端到端（spec §6.4）：publishEvent → NotificationEventListener → NotificationService → 落库。
 *  数据前缀 NTF-*（共享容器，全局唯一）。best-effort 失败注入在单测层（NotificationEventListenerTest），
 *  此处覆盖真实发布→落库闭环。 */
class M10NotificationEventIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var publisher: ApplicationEventPublisher
    @Autowired lateinit var notificationRepository: NotificationRepository
    @Autowired lateinit var projectRepository: ProjectRepository

    @Test
    fun `waiver event is persisted as IN_APP notification for the actor`() {
        publisher.publishEvent(RemediationWaiverEvent(projectId = 9901L, findingId = 1L, actorId = 7L, reason = "业务豁免"))
        val rows = notificationRepository.findByRecipient("7")
        assertTrue(rows.any { it.type == "REMEDIATION_WAIVER" && it.status == "SENT" && it.title == "finding waived" })
    }

    @Test
    fun `regression event notifies the project owner`() {
        val project = projectRepository.save(Project().apply {
            code = "M10REGRESS"; name = "m10 regression"; ownerUserId = 7L
        })
        publisher.publishEvent(FindingRegressionEvent(project.id!!, 8801L, listOf(2L, 3L)))
        val rows = notificationRepository.findByRecipient("7")
        assertTrue(rows.any { it.type == "FINDING_REGRESSION" && it.status == "SENT" && it.title == "finding regressed" })
    }
}
