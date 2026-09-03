package com.example.compliance.report.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.result.domain.Finding
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.scan.domain.ChecklistItemResult
import com.example.compliance.scan.domain.ComplianceEvaluation
import com.example.compliance.scan.domain.ScanTask
import com.example.compliance.scan.domain.ScanTaskStatus
import com.example.compliance.scan.infrastructure.ChecklistItemResultRepository
import com.example.compliance.scan.infrastructure.ComplianceEvaluationRepository
import com.example.compliance.scan.infrastructure.ScanTaskRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReportServiceTest {
    private val scanTaskRepository = mockk<ScanTaskRepository>(relaxed = true)
    private val findingRepository = mockk<FindingRepository>(relaxed = true)
    private val evaluationRepository = mockk<ComplianceEvaluationRepository>(relaxed = true)
    private val itemResultRepository = mockk<ChecklistItemResultRepository>(relaxed = true)
    private val service = ReportService(scanTaskRepository, findingRepository, evaluationRepository, itemResultRepository)

    @Test
    fun `scanSummary groups findings by severity`() {
        every { scanTaskRepository.findById(1L) } returns Optional.of(
            ScanTask().apply { id = 1L; projectId = 1L; engine = "SEMGREP"; status = ScanTaskStatus.SUCCESS }
        )
        every { findingRepository.findByProjectScanTask(1L) } returns listOf(
            Finding().apply { severity = "HIGH" },
            Finding().apply { severity = "HIGH" },
            Finding().apply { severity = "MEDIUM" },
        )
        val summary = service.scanSummary(1L)
        assertEquals(3, summary.findingCount)
        assertEquals(2, summary.bySeverity["HIGH"])
        assertEquals(1, summary.bySeverity["MEDIUM"])
    }

    @Test
    fun `complianceSummary returns latest evaluation with item results`() {
        every { evaluationRepository.findFirstByProjectIdOrderByIdDesc(1L) } returns
            ComplianceEvaluation().apply {
                id = 5L; projectId = 1L; totalItems = 2; passed = 1; failed = 1; score = BigDecimal("50.00")
            }
        every { itemResultRepository.findByEvaluationId(5L) } returns listOf(
            ChecklistItemResult().apply { itemCode = "SEC-001"; result = "FAIL"; findingCount = 2 },
            ChecklistItemResult().apply { itemCode = "SEC-002"; result = "PASS"; findingCount = 0 },
        )
        val summary = service.complianceSummary(1L)
        assertEquals(5L, summary.evaluationId)
        assertEquals(2, summary.items.size)
        assertEquals("FAIL", summary.items[0].result)
    }

    @Test
    fun `complianceSummary throws when no evaluation`() {
        every { evaluationRepository.findFirstByProjectIdOrderByIdDesc(1L) } returns null
        assertFailsWith<BusinessException> { service.complianceSummary(1L) }
    }
}
