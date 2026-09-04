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
import kotlin.test.assertFailsWith

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

    @Test
    fun `dependency finding uses dependency fingerprint and persists dependency fields`() {
        val fpDep = "fp-dep"
        val dep = NewFinding("M11TRV", "lodash proto pollution", "package-lock.json", null, "CRITICAL", null, "pollution", null,
            "lodash", "4.17.20", "4.17.21", "CVE-2024-1234", 9.8)
        every { fingerprintGenerator.generateDependency(9L, "lodash", "4.17.20", "CVE-2024-1234") } returns fpDep
        every { findingRepository.findByProjectIdAndFingerprint(9L, fpDep) } returns null
        every { findingRepository.save(any<Finding>()) } answers { firstArg<Finding>().apply { id = 1L } }
        every { historyRepository.save(any<FindingHistory>()) } answers { firstArg<FindingHistory>() }

        val result = findingService.upsertByFingerprint(9L, 60L, "TRIVY", listOf(dep))

        assertEquals(UpsertResult(1, 0), result)
        verify { findingRepository.save(match {
            it.fingerprint == fpDep && it.packageName == "lodash" && it.cveId == "CVE-2024-1234" &&
            it.packageVersion == "4.17.20" && it.fixedVersion == "4.17.21" && it.cvssScore == 9.8.toBigDecimal()
        }) }
        verify { historyRepository.save(match { it.action == "CREATED" && it.scanTaskId == 60L }) }
    }

    @Test
    fun `reappearing dependency finding increments occurrence and keeps active state`() {
        val fpDep = "fp-dep-re"
        val existing = Finding().apply { id = 7L; projectId = 9L; status = FindingStatus.NEW; fingerprint = fpDep; occurrenceCount = 1 }
        every { fingerprintGenerator.generateDependency(9L, "lodash", "4.17.20", "CVE-2024-1234") } returns fpDep
        every { findingRepository.findByProjectIdAndFingerprint(9L, fpDep) } returns existing
        every { findingRepository.save(any<Finding>()) } answers { firstArg() }
        every { historyRepository.save(any<FindingHistory>()) } answers { firstArg<FindingHistory>() }

        val dep = NewFinding("M11TRV", "lodash proto pollution", "package-lock.json", null, "CRITICAL", null, "pollution", null,
            "lodash", "4.17.20", "4.17.21", "CVE-2024-1234", 9.8)
        val result = findingService.upsertByFingerprint(9L, 61L, "TRIVY", listOf(dep))

        assertEquals(UpsertResult(0, 1), result)
        assertEquals(FindingStatus.NEW, existing.status)   // 活动集 → 状态保持
        assertEquals(2, existing.occurrenceCount)
        verify { historyRepository.save(match { it.action == "REAPPEARED" && it.scanTaskId == 61L }) }
    }

    @Test
    fun `code finding path is unchanged`() {
        val fp = "fp-code"
        every { fingerprintGenerator.generate(9L, "R1", "A.java", 1, "s") } returns fp
        every { findingRepository.findByProjectIdAndFingerprint(9L, fp) } returns null
        every { findingRepository.save(any<Finding>()) } answers { firstArg<Finding>().apply { id = 1L } }
        every { historyRepository.save(any<FindingHistory>()) } answers { firstArg<FindingHistory>() }

        findingService.upsertByFingerprint(9L, 62L, "STUB", listOf(newFinding))

        verify { findingRepository.save(match { it.fingerprint == fp && it.packageName == null && it.cveId == null }) }
        verify(exactly = 0) { fingerprintGenerator.generateDependency(any(), any(), any(), any()) }
    }

    @Test
    fun `reappearing dependency finding refreshes remediation metadata P3-D7`() {
        val fpDep = "fp-dep-refresh"
        val existing = Finding().apply {
            id = 8L; projectId = 9L; status = FindingStatus.NEW; fingerprint = fpDep; occurrenceCount = 1
            packageName = "lodash"; cveId = "CVE-2024-1234"
            packageVersion = "4.17.19"; fixedVersion = "4.17.20"; cvssScore = 7.5.toBigDecimal()
        }
        every { fingerprintGenerator.generateDependency(9L, "lodash", "4.17.20", "CVE-2024-1234") } returns fpDep
        every { findingRepository.findByProjectIdAndFingerprint(9L, fpDep) } returns existing
        every { findingRepository.save(any<Finding>()) } answers { firstArg() }
        every { historyRepository.save(any<FindingHistory>()) } answers { firstArg<FindingHistory>() }

        val dep = NewFinding("M11TRV", "lodash proto pollution", "package-lock.json", null, "CRITICAL", null, "pollution", null,
            "lodash", "4.17.20", "4.17.21", "CVE-2024-1234", 9.8)
        val result = findingService.upsertByFingerprint(9L, 63L, "TRIVY", listOf(dep))

        assertEquals(UpsertResult(0, 1), result)
        assertEquals(2, existing.occurrenceCount)
        assertEquals("4.17.20", existing.packageVersion)   // 刷新自 incoming —— advisory 更新后不陈旧
        assertEquals("4.17.21", existing.fixedVersion)
        assertEquals(9.8.toBigDecimal(), existing.cvssScore)
        assertEquals(FindingStatus.NEW, existing.status)   // P2-D4: 不碰 status
    }

    @Test
    fun `single-sided dependency finding throws IllegalArgumentException P3-D8`() {
        // both-or-neither: 仅 packageName 或仅 cveId 是上游适配器 bug，显式失败优于 NPE
        val packageOnly = NewFinding("M11TRV", "p", "package-lock.json", null, "HIGH", null, null, null,
            "lodash", null, null, null, null)
        assertFailsWith<IllegalArgumentException> {
            findingService.upsertByFingerprint(9L, 64L, "TRIVY", listOf(packageOnly))
        }
        val cveOnly = NewFinding("M11TRV", "p", "package-lock.json", null, "HIGH", null, null, null,
            null, null, null, "CVE-2024-1234", null)
        assertFailsWith<IllegalArgumentException> {
            findingService.upsertByFingerprint(9L, 65L, "TRIVY", listOf(cveOnly))
        }
    }
}
