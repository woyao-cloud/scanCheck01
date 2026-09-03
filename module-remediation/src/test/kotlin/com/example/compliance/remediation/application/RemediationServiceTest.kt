package com.example.compliance.remediation.application

import com.example.compliance.remediation.domain.RemediationTask
import com.example.compliance.remediation.infrastructure.RemediationTaskRepository
import com.example.compliance.result.application.FindingLifecyclePort
import com.example.compliance.result.application.FindingView
import com.example.compliance.result.domain.FindingStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** M7 单元测试：状态权威在 finding，task.status 仅镜像（P2-D4）。 */
class RemediationServiceTest {

    private val taskRepository = mockk<RemediationTaskRepository>()
    private val lifecyclePort = mockk<FindingLifecyclePort>()
    private val service = RemediationService(taskRepository, lifecyclePort)

    private fun view(status: FindingStatus) = FindingView(
        id = 7L, projectId = 9L, scanTaskId = 1L, ruleCode = "R1", severity = "HIGH",
        status = status, filePath = "A.java", lineNumber = 1,
        firstSeenAt = Instant.now(), lastSeenAt = Instant.now(), occurrenceCount = 1,
    )

    @Test
    fun `assign creates task and mirrors assigned status`() {
        val saved = RemediationTask().apply { id = 11L; findingId = 7L; assigneeUserId = 3L; createdAt = Instant.now() }
        every { taskRepository.findByFindingId(7L) } returns null
        every { taskRepository.save(any<RemediationTask>()) } returns saved
        every { lifecyclePort.findById(7L) } returns view(FindingStatus.CONFIRMED)
        every { lifecyclePort.transition(7L, FindingStatus.ASSIGNED, "assigned", 9L) } returns FindingStatus.ASSIGNED

        val result = service.assign(7L, 9L, 3L, "fix in sprint", null)

        assertNotNull(result.task?.id)
        assertEquals(7L, result.finding.id)
        assertEquals(FindingStatus.ASSIGNED, result.finding.status)
        assertEquals(FindingStatus.ASSIGNED, result.task?.status)   // 镜像
        assertEquals(3L, result.task?.assigneeUserId)
        verify { lifecyclePort.transition(7L, FindingStatus.ASSIGNED, "assigned", 9L) }
    }

    @Test
    fun `assign rejects finding not in confirmed state`() {
        every { lifecyclePort.findById(7L) } returns view(FindingStatus.NEW)
        val ex = org.junit.jupiter.api.assertThrows<com.example.compliance.common.exception.BusinessException> {
            service.assign(7L, 9L, 3L, null, null)
        }
        assertEquals("finding not in CONFIRMED state: 7", ex.message)
    }

    @Test
    fun `get returns view with null task before assign`() {
        every { taskRepository.findByFindingId(8L) } returns null
        every { lifecyclePort.findById(8L) } returns view(FindingStatus.NEW)
        val result = service.get(8L)
        assertNull(result.task)
        assertEquals(FindingStatus.NEW, result.finding.status)
    }

    @Test
    fun `confirm requires NEW`() {
        every { lifecyclePort.findById(7L) } returns view(FindingStatus.CONFIRMED)
        val ex = org.junit.jupiter.api.assertThrows<com.example.compliance.common.exception.BusinessException> {
            service.confirm(7L, 9L)
        }
        assertEquals("finding not in NEW state: 7", ex.message)
    }

    @Test
    fun `fixed requires evidence and transitions from fixing`() {
        every { lifecyclePort.findById(7L) } returnsMany listOf(
            view(FindingStatus.FIXING),   // markFixed 守卫读
            view(FindingStatus.FIXED),    // mirrorTransition 内 get 重读
        )
        every { taskRepository.findByFindingId(7L) } returns RemediationTask().apply { id = 11L; findingId = 7L; createdAt = Instant.now() }
        every { taskRepository.save(any<RemediationTask>()) } answers { firstArg() }
        every { lifecyclePort.addEvidence(7L, "FIX_COMMIT", "deadbeef", 9L) } returns
            com.example.compliance.result.application.EvidenceView(1L, 7L, "FIX_COMMIT", "deadbeef", 9L, java.time.Instant.EPOCH)
        every { lifecyclePort.transition(7L, FindingStatus.FIXED, "fixed", 9L) } returns FindingStatus.FIXED

        val result = service.markFixed(7L, 9L, "FIX_COMMIT", "deadbeef")

        assertEquals(FindingStatus.FIXED, result.finding.status)
        verify { lifecyclePort.addEvidence(7L, "FIX_COMMIT", "deadbeef", 9L) }
    }

    @Test
    fun `fixed without evidence is rejected`() {
        every { lifecyclePort.findById(7L) } returns view(FindingStatus.FIXING)
        val ex = org.junit.jupiter.api.assertThrows<com.example.compliance.common.exception.BusinessException> {
            service.markFixed(7L, 9L, "", "")
        }
        assertEquals("evidence required for fixed", ex.message)
    }
}
