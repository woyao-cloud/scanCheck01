package com.example.compliance.notification

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.common.event.FindingRegressionEvent
import com.example.compliance.common.event.RemediationAssignedEvent
import com.example.compliance.common.event.RemediationCompletedEvent
import com.example.compliance.common.event.ReportSnapshotGeneratedEvent
import com.example.compliance.common.event.ScanCompletedEvent
import com.example.compliance.notification.infrastructure.NotificationRepository
import com.example.compliance.project.domain.Project
import com.example.compliance.project.infrastructure.ProjectRepository
import com.example.compliance.user.domain.User
import com.example.compliance.user.infrastructure.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.assertTrue

/** M17 事件接线 e2e：publishEvent → 监听器（owner/assignee/actor 解析）→ notify → 落库。
 *  回归 emptyList bug 修复实证；webhook 未配置 → 无 WEBHOOK 行。
 *  数据前缀：项目 M17EVT1..4、用户 m17evt-*（跨类全局唯一，共享容器）。 */
class M17NotificationEventIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var publisher: ApplicationEventPublisher
    @Autowired lateinit var repository: NotificationRepository
    @Autowired lateinit var projectRepository: ProjectRepository
    @Autowired lateinit var userRepository: UserRepository

    private fun user(username: String): Long =
        userRepository.save(User().apply { this.username = username; passwordHash = "x" }).id!!

    private fun project(code: String, ownerId: Long?): Long =
        projectRepository.save(Project().apply { this.code = code; name = "m17"; ownerUserId = ownerId }).id!!

    @Test
    fun `scan completed and report snapshot notify project owner`() {
        val owner = user("m17evt-owner")
        val projectId = project("M17EVT1", owner)

        publisher.publishEvent(ScanCompletedEvent(901L, projectId, "SUCCESS"))
        publisher.publishEvent(ReportSnapshotGeneratedEvent(901L, projectId, "COMPLIANCE"))

        val rows = repository.findByRecipient(owner.toString())
        assertTrue(rows.any { it.type == "SCAN_COMPLETED" && it.title == "scan completed" && it.status == "SENT" })
        assertTrue(rows.any { it.type == "REPORT_SNAPSHOT_GENERATED" && it.title == "report snapshot generated" && it.status == "SENT" })
    }

    @Test
    fun `remediation assigned and completed notify their recipients`() {
        val assignee = user("m17evt-assignee")
        val actor = user("m17evt-actor")

        publisher.publishEvent(RemediationAssignedEvent(7L, 99L, assignee))
        publisher.publishEvent(RemediationCompletedEvent(7L, 99L, actor, assignee))

        assertTrue(repository.findByRecipient(assignee.toString()).any { it.type == "REMEDIATION_ASSIGNED" && it.title == "remediation assigned" })
        assertTrue(repository.findByRecipient(actor.toString()).any { it.type == "REMEDIATION_COMPLETED" && it.title == "remediation completed" })
        assertTrue(repository.findByRecipient(assignee.toString()).any { it.type == "REMEDIATION_COMPLETED" })
    }

    @Test
    fun `regression event notifies the project owner - emptyList bug fixed`() {
        val owner = user("m17evt-owner3")
        val projectId = project("M17EVT3", owner)

        publisher.publishEvent(FindingRegressionEvent(projectId, 8801L, listOf(2L, 3L)))

        val rows = repository.findByRecipient(owner.toString())
        assertTrue(rows.any { it.type == "FINDING_REGRESSION" && it.title == "finding regressed" && it.status == "SENT" })
    }

    @Test
    fun `no webhook row when url not configured`() {
        val owner = user("m17evt-owner4")
        val projectId = project("M17EVT4", owner)

        publisher.publishEvent(ScanCompletedEvent(902L, projectId, "FAILED"))

        assertTrue(repository.findByStatusAndChannel("PENDING", "WEBHOOK").none { it.type == "SCAN_COMPLETED" })
    }
}
