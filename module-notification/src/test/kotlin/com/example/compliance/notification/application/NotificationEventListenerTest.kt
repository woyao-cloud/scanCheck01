package com.example.compliance.notification.application

import com.example.compliance.common.event.FindingRegressionEvent
import com.example.compliance.common.event.RemediationAssignedEvent
import com.example.compliance.common.event.RemediationCompletedEvent
import com.example.compliance.common.event.RemediationWaiverEvent
import com.example.compliance.common.event.ReportSnapshotGeneratedEvent
import com.example.compliance.common.event.ScanCompletedEvent
import com.example.compliance.project.domain.Project
import com.example.compliance.project.infrastructure.ProjectRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.Optional

class NotificationEventListenerTest {
    private val service = mockk<NotificationService>()
    private val projectRepository = mockk<ProjectRepository>()
    private val listener = NotificationEventListener(service, projectRepository)

    private fun project(owner: Long?) = Project().apply { this.id = 1L; ownerUserId = owner }

    @Test
    fun `regression resolves project owner as recipient with semantic variables`() {
        every { projectRepository.findById(1L) } returns Optional.of(project(7L))
        listener.onRegression(FindingRegressionEvent(1L, 2L, listOf(3L)))
        verify { service.notify("FINDING_REGRESSION", mapOf<String, Any>("findingCount" to 1, "scanTaskId" to 2L), listOf(7L)) }
    }

    @Test
    fun `regression with no owner notifies nothing`() {
        every { projectRepository.findById(1L) } returns Optional.of(project(null))
        listener.onRegression(FindingRegressionEvent(1L, 2L, listOf(3L)))
        verify(exactly = 0) { service.notify(any(), any(), any()) }
    }

    @Test
    fun `regression with missing project notifies nothing`() {
        every { projectRepository.findById(99L) } returns Optional.empty()
        listener.onRegression(FindingRegressionEvent(99L, 2L, listOf(3L)))
        verify(exactly = 0) { service.notify(any(), any(), any()) }
    }

    @Test
    fun `waiver notifies actor`() {
        listener.onWaiver(RemediationWaiverEvent(1L, 2L, 3L, "r"))
        verify { service.notify("REMEDIATION_WAIVER", mapOf("findingId" to 2L, "reason" to "r"), listOf(3L)) }
    }

    @Test
    fun `service failure does not propagate`() {
        every { projectRepository.findById(1L) } returns Optional.of(project(7L))
        every { service.notify(any(), any(), any()) } throws RuntimeException("boom")
        listener.onRegression(FindingRegressionEvent(1L, 2L, listOf(3L)))   // 不抛 —— runCatching 兜底
        listener.onWaiver(RemediationWaiverEvent(1L, 2L, 3L, "r"))
    }

    @Test
    fun `scan completed resolves owner`() {
        every { projectRepository.findById(1L) } returns Optional.of(project(7L))
        listener.onScanCompleted(ScanCompletedEvent(1L, 1L, "SUCCESS"))
        verify { service.notify("SCAN_COMPLETED", mapOf("scanTaskId" to 1L, "status" to "SUCCESS"), listOf(7L)) }
    }

    @Test
    fun `scan completed with no owner notifies nothing`() {
        every { projectRepository.findById(1L) } returns Optional.of(project(null))
        listener.onScanCompleted(ScanCompletedEvent(1L, 1L, "FAILED"))
        verify(exactly = 0) { service.notify(any(), any(), any()) }
    }

    @Test
    fun `report snapshot generated resolves owner`() {
        every { projectRepository.findById(1L) } returns Optional.of(project(7L))
        listener.onReportSnapshotGenerated(ReportSnapshotGeneratedEvent(5L, 1L, "COMPLIANCE"))
        verify { service.notify("REPORT_SNAPSHOT_GENERATED", mapOf("snapshotId" to 5L, "snapshotType" to "COMPLIANCE"), listOf(7L)) }
    }

    @Test
    fun `report snapshot with null project notifies nothing`() {
        listener.onReportSnapshotGenerated(ReportSnapshotGeneratedEvent(5L, null, "SCAN_SUMMARY"))
        verify(exactly = 0) { service.notify(any(), any(), any()) }
    }

    @Test
    fun `remediation assigned notifies assignee`() {
        listener.onRemediationAssigned(RemediationAssignedEvent(3L, 9L, 4L))
        verify { service.notify("REMEDIATION_ASSIGNED", mapOf("findingId" to 3L), listOf(4L)) }
    }

    @Test
    fun `remediation completed notifies actor and assignee deduped`() {
        listener.onRemediationCompleted(RemediationCompletedEvent(3L, 9L, 4L, 4L))
        verify { service.notify("REMEDIATION_COMPLETED", mapOf("findingId" to 3L), listOf(4L)) }
    }

    @Test
    fun `remediation completed notifies actor and assignee`() {
        listener.onRemediationCompleted(RemediationCompletedEvent(3L, 9L, 4L, 5L))
        verify { service.notify("REMEDIATION_COMPLETED", mapOf("findingId" to 3L), listOf(4L, 5L)) }
    }
}
