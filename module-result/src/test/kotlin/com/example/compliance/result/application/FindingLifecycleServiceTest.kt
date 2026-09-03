package com.example.compliance.result.application

import com.example.compliance.common.audit.AuditService
import com.example.compliance.common.event.FindingRegressionEvent
import com.example.compliance.result.domain.Finding
import com.example.compliance.result.domain.FindingEvidence
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.result.domain.FindingStatusSnapshot
import com.example.compliance.result.infrastructure.FindingEvidenceRepository
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.result.infrastructure.FindingStatusSnapshotRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
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
    private val eventPublisher = mockk<org.springframework.context.ApplicationEventPublisher>()
    private val service = FindingLifecycleService(findingRepository, statusRepository, evidenceRepository, auditService, eventPublisher)

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
        // 命中回归（regressed=1）→ 发布回归事件；strict mock 需 stub Unit 方法。
        // any<Any>() 显式类型强制解析到 publishEvent(Object) 重载（事件类是普通值类型，非 ApplicationEvent）。
        every { eventPublisher.publishEvent(any<Any>()) } just Runs

        // 复扫命中 f1（present），f2/f3 缺席；目标集 = 全部三个 finding（保持原语义）
        val result = service.verifyRechecking(5L, 99L, setOf(1L), targetFindingIds = setOf(1L, 2L, 3L))

        assertEquals(VerifyResult(closed = 2, regressed = 1), result)
        assertEquals(FindingStatus.CONFIRMED, fixed1.status)
        assertEquals(FindingStatus.CLOSED, fixed2.status)
        assertEquals(FindingStatus.CLOSED, fixed3.status)
        verify { statusRepository.save(any<FindingStatusSnapshot>()) }
        verify { eventPublisher.publishEvent(match<Any> { it is FindingRegressionEvent && it.findingIds == listOf(1L) }) }
    }

    @Test
    fun `verifyRechecking only touches target finding ids`() {
        val f1 = Finding().apply { id = 1L; projectId = 9L; status = FindingStatus.RECHECKING; fingerprint = "t1" }
        val f2 = Finding().apply { id = 2L; projectId = 9L; status = FindingStatus.RECHECKING; fingerprint = "t2" }
        every { findingRepository.findAll() } returns listOf(f1, f2)
        every { findingRepository.save(any()) } answers { firstArg() }
        every { findingRepository.findById(any()) } answers { firstArg<Long>().let { id ->
            java.util.Optional.of(listOf(f1, f2).first { it.id == id })
        } }
        every { statusRepository.save(any()) } answers { firstArg() }

        // target 集只含 id=1 → 只有 id=1 被转移（缺席→CLOSED）；id=2 不被碰
        val result = service.verifyRechecking(9L, 99L, presentFindingIds = emptySet(), targetFindingIds = setOf(1L))

        assertEquals(VerifyResult(closed = 1, regressed = 0), result)
        assertEquals(FindingStatus.CLOSED, f1.status)
        assertEquals(FindingStatus.RECHECKING, f2.status)   // 目标集外保持 RECHECKING
        verify(exactly = 1) { findingRepository.findById(1L) }
        verify(exactly = 0) { findingRepository.findById(2L) }
    }

    @Test
    fun `verifyRechecking with empty target ids is a no-op`() {
        val f1 = Finding().apply { id = 1L; projectId = 9L; status = FindingStatus.RECHECKING; fingerprint = "t1" }
        every { findingRepository.findAll() } returns listOf(f1)
        // 空 target → 无 transition → 不触碰任何 finding（不产生 save/findById）
        val result = service.verifyRechecking(9L, 99L, presentFindingIds = emptySet(), targetFindingIds = emptySet())
        assertEquals(VerifyResult(0, 0), result)
        verify(exactly = 0) { findingRepository.findById(any()) }
    }

    @Test
    fun `addEvidence persists evidence`() {
        // saved entity needs a non-null id for the service's EvidenceView mapping (EvidenceView.id)
        every { evidenceRepository.save(any<FindingEvidence>()) } answers { firstArg<FindingEvidence>().apply { id = 1L } }
        val evidence = service.addEvidence(7L, "FIX_COMMIT", "abc123", 3L)
        assertEquals(7L, evidence.findingId)
        assertEquals("FIX_COMMIT", evidence.evidenceType)
    }

    @Test
    fun `findingsGlobal filters by project status and severity`() {
        val f1 = Finding().apply { id = 1L; projectId = 9L; severity = "HIGH"; status = FindingStatus.NEW; engine = "SEMGREP"; ruleCode = "R1"; filePath = "A.java"; fingerprint = "g1" }
        val f2 = Finding().apply { id = 2L; projectId = 9L; severity = "LOW"; status = FindingStatus.NEW; engine = "SEMGREP"; ruleCode = "R1"; filePath = "A.java"; fingerprint = "g2" }
        val f3 = Finding().apply { id = 3L; projectId = 8L; severity = "HIGH"; status = FindingStatus.NEW; engine = "SEMGREP"; ruleCode = "R1"; filePath = "A.java"; fingerprint = "g3" }
        every { findingRepository.findAll() } returns listOf(f1, f2, f3)
        val result = service.findingsGlobal(projectId = 9L, status = FindingStatus.NEW, severity = "HIGH")
        assertEquals(listOf(1L), result.map { it.id })
    }
}
