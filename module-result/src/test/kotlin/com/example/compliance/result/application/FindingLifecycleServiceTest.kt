package com.example.compliance.result.application

import com.example.compliance.common.audit.AuditService
import com.example.compliance.result.domain.Finding
import com.example.compliance.result.domain.FindingEvidence
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.result.domain.FindingStatusSnapshot
import com.example.compliance.result.infrastructure.FindingEvidenceRepository
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.result.infrastructure.FindingStatusSnapshotRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FindingLifecycleServiceTest {

    private val findingRepository = mockk<FindingRepository>()
    private val statusRepository = mockk<FindingStatusSnapshotRepository>()
    private val evidenceRepository = mockk<FindingEvidenceRepository>()
    private val auditService = mockk<AuditService>(relaxed = true)
    private val service = FindingLifecycleService(findingRepository, statusRepository, evidenceRepository, auditService)

    @Test
    fun `transition writes snapshot and audit and updates finding status`() {
        val finding = Finding().apply { id = 7L; status = FindingStatus.NEW }
        every { findingRepository.findById(7L) } returns java.util.Optional.of(finding)
        every { findingRepository.save(finding) } returns finding
        // statusRepository is a strict mock: transition() calls save() before the trailing verify, so it must be stubbed.
        every { statusRepository.save(any()) } answers { firstArg() }

        val result = service.transition(7L, FindingStatus.CONFIRMED, "verified", 3L)

        assertSame(FindingStatus.CONFIRMED, result)
        assertEquals(FindingStatus.CONFIRMED, finding.status)
        verify { statusRepository.save(any<FindingStatusSnapshot>()) }
        verify { auditService.record(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `verifyRechecking closes absent and regresses present findings`() {
        val fixed1 = Finding().apply { id = 1L; projectId = 5L; status = FindingStatus.RECHECKING; fingerprint = "f1" }
        val fixed2 = Finding().apply { id = 2L; projectId = 5L; status = FindingStatus.RECHECKING; fingerprint = "f2" }
        val fixed3 = Finding().apply { id = 3L; projectId = 5L; status = FindingStatus.RECHECKING; fingerprint = "f3" }
        every { findingRepository.findAll() } returns listOf(fixed1, fixed2, fixed3)
        every { findingRepository.save(any()) } answers { firstArg() }
        // R-6.3-a: transition re-fetches via findById on a non-relaxed mock; stub it against the fixed instances.
        every { findingRepository.findById(any()) } answers { firstArg<Long>().let { id ->
            java.util.Optional.of(listOf(fixed1, fixed2, fixed3).first { it.id == id })
        } }
        // statusRepository is a strict mock: transition() calls save() before the trailing verify, so it must be stubbed.
        every { statusRepository.save(any()) } answers { firstArg() }

        // 复扫命中 f1（present），f2/f3 缺席
        val result = service.verifyRechecking(5L, 99L, setOf(1L))

        assertEquals(VerifyResult(closed = 2, regressed = 1), result)
        assertEquals(FindingStatus.CONFIRMED, fixed1.status)
        assertEquals(FindingStatus.CLOSED, fixed2.status)
        assertEquals(FindingStatus.CLOSED, fixed3.status)
        verify { statusRepository.save(any<FindingStatusSnapshot>()) }
    }

    @Test
    fun `addEvidence persists evidence`() {
        // saved entity needs a non-null id for the service's EvidenceView mapping (EvidenceView.id)
        every { evidenceRepository.save(any<FindingEvidence>()) } answers { firstArg<FindingEvidence>().apply { id = 1L } }
        val evidence = service.addEvidence(7L, "FIX_COMMIT", "abc123", 3L)
        assertEquals(7L, evidence.findingId)
        assertEquals("FIX_COMMIT", evidence.evidenceType)
    }
}
