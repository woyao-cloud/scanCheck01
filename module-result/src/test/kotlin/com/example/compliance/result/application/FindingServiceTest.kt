package com.example.compliance.result.application

import com.example.compliance.result.domain.Finding
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.result.domain.FindingHistory
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.result.infrastructure.FindingHistoryRepository
import com.example.compliance.result.infrastructure.FingerprintGenerator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals

class FindingServiceTest {
    private val findingRepository = mockk<FindingRepository>(relaxed = true)
    private val traceRepository = mockk<FindingHistoryRepository>(relaxed = true)
    private val service = FindingService(findingRepository, traceRepository, FingerprintGenerator())

    private fun newFinding(code: String, file: String, line: Int, severity: String = "HIGH") =
        NewFinding(code, "rule", file, line, severity, "SEC", "msg", "snippet")

    @Test
    fun `new fingerprint inserts finding and records CREATED trace`() {
        every { findingRepository.findByFingerprint(any()) } returns null
        every { findingRepository.save(any()) } answers { firstArg<Finding>().apply { id = 1L } }
        every { traceRepository.save(any()) } answers { firstArg<FindingHistory>() }
        val result = service.upsertByFingerprint(1L, 100L, "SEMGREP", listOf(newFinding("SEC-001", "A.java", 1)))
        assertEquals(1, result.created)
        assertEquals(0, result.updated)
        verify { traceRepository.save(match { it.action == "CREATED" }) }
    }

    @Test
    fun `existing fingerprint increments occurrence and records UPDATED trace`() {
        every { findingRepository.findByFingerprint(any()) } returns
            Finding().apply { id = 9L; occurrenceCount = 1; status = FindingStatus.FIXED }
        every { findingRepository.save(any()) } answers { firstArg<Finding>() }
        every { traceRepository.save(any()) } answers { firstArg<FindingHistory>() }
        val result = service.upsertByFingerprint(1L, 100L, "SEMGREP", listOf(newFinding("SEC-001", "A.java", 1)))
        assertEquals(1, result.updated)
        verify { traceRepository.save(match { it.action == "UPDATED" }) }
    }
}
