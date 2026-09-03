package com.example.compliance.result.application

import com.example.compliance.result.domain.Finding
import com.example.compliance.result.domain.FindingHistory
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.result.infrastructure.FindingHistoryRepository
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.result.infrastructure.FingerprintGenerator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FindingServiceTest {

    private val findingRepository = mockk<FindingRepository>()
    private val historyRepository = mockk<FindingHistoryRepository>()
    private val fingerprintGenerator = mockk<FingerprintGenerator>()
    private val lifecycleService = mockk<FindingLifecycleService>()
    private val findingService = FindingService(findingRepository, historyRepository, fingerprintGenerator, lifecycleService)

    private val newFinding = NewFinding("R1", "r", "A.java", 1, "HIGH", null, null, "s")

    @Test
    fun `existing finding in active state keeps status and counts occurrence`() {
        val fp = "fp-active"
        val existing = Finding().apply { id = 1L; projectId = 9L; status = FindingStatus.ASSIGNED; fingerprint = fp }
        every { findingRepository.findByProjectIdAndFingerprint(9L, fp) } returns existing
        every { findingRepository.save(any<Finding>()) } answers { firstArg() }
        every { historyRepository.save(any<FindingHistory>()) } answers { firstArg<FindingHistory>() }
        every { fingerprintGenerator.generate(9L, "R1", "A.java", 1, "s") } returns fp

        val result = findingService.upsertByFingerprint(9L, 50L, "STUB", listOf(newFinding))

        assertEquals(UpsertResult(0, 1), result)
        assertEquals(FindingStatus.ASSIGNED, existing.status)   // 活动集 → 状态不变
        assertEquals(2, existing.occurrenceCount)
        verify { historyRepository.save(match { it.action == "REAPPEARED" && it.scanTaskId == 50L }) }
    }

    @Test
    fun `fixed finding reappearing regresses to confirmed`() {
        val fp = "fp-regress"
        val fixed = Finding().apply { id = 2L; projectId = 9L; status = FindingStatus.FIXED; fingerprint = fp }
        every { findingRepository.findByProjectIdAndFingerprint(9L, fp) } returns fixed
        every { findingRepository.save(any<Finding>()) } answers { firstArg() }
        every { historyRepository.save(any<FindingHistory>()) } answers { firstArg<FindingHistory>() }
        every { fingerprintGenerator.generate(9L, "R1", "A.java", 1, "s") } returns fp
        every { lifecycleService.transition(2L, FindingStatus.CONFIRMED, "reappeared_after_fix", null) } returns FindingStatus.CONFIRMED

        findingService.upsertByFingerprint(9L, 51L, "STUB", listOf(newFinding))

        verify { lifecycleService.transition(2L, FindingStatus.CONFIRMED, "reappeared_after_fix", null) }
    }

    @Test
    fun `waived finding stays terminal`() {
        val fp = "fp-waived"
        val waived = Finding().apply { id = 3L; projectId = 9L; status = FindingStatus.WAIVED; fingerprint = fp }
        every { findingRepository.findByProjectIdAndFingerprint(9L, fp) } returns waived
        every { findingRepository.save(any<Finding>()) } answers { firstArg() }
        every { historyRepository.save(any<FindingHistory>()) } answers { firstArg<FindingHistory>() }
        every { fingerprintGenerator.generate(9L, "R1", "A.java", 1, "s") } returns fp

        findingService.upsertByFingerprint(9L, 52L, "STUB", listOf(newFinding))

        assertEquals(FindingStatus.WAIVED, waived.status)   // 豁免集 → 保持终态
        verify(exactly = 0) { lifecycleService.transition(any(), any(), any(), any()) }
    }

    @Test
    fun `new fingerprint inserts finding and records CREATED trace`() {
        val fp = "fp-new"
        every { fingerprintGenerator.generate(9L, "R1", "A.java", 1, "s") } returns fp
        every { findingRepository.findByProjectIdAndFingerprint(9L, fp) } returns null
        every { findingRepository.save(any<Finding>()) } answers { firstArg<Finding>().apply { id = 1L } }
        every { historyRepository.save(any<FindingHistory>()) } answers { firstArg<FindingHistory>() }

        val result = findingService.upsertByFingerprint(9L, 50L, "STUB", listOf(NewFinding("R1", "r", "A.java", 1, "HIGH", null, null, "s")))

        assertEquals(UpsertResult(1, 0), result)
        verify { historyRepository.save(match { it.action == "CREATED" && it.scanTaskId == 50L }) }
    }
}
