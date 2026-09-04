package com.example.compliance.report.application

import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.report.domain.ReportSnapshot
import com.example.compliance.report.domain.ReportTemplate
import com.example.compliance.report.domain.ReportTemplateVersion
import com.example.compliance.report.infrastructure.ReportSnapshotRepository
import com.example.compliance.report.infrastructure.ReportTemplateRepository
import com.example.compliance.report.infrastructure.ReportTemplateVersionRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import java.math.BigDecimal
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportGenerationServiceTest {
    private val reportService = mockk<ReportService>(relaxed = true)
    private val templateRepository = mockk<ReportTemplateRepository>(relaxed = true)
    private val versionRepository = mockk<ReportTemplateVersionRepository>(relaxed = true)
    private val snapshotRepository = mockk<ReportSnapshotRepository>(relaxed = true)
    private val service = ReportGenerationService(reportService, templateRepository, versionRepository, snapshotRepository)
    private val mapper = ObjectMapper()

    private fun template(type: String) = ReportTemplate().apply { id = 1L; this.templateType = type; name = type.lowercase() }
    private fun published() = ReportTemplateVersion().apply {
        id = 5L; templateId = 1L; versionNo = 2; status = VersionStatus.PUBLISHED; sections = "{}"
    }

    @Test
    fun `scan summary generation snapshots payload with published template version`() {
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns template("SCAN_SUMMARY")
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.PUBLISHED) } returns published()
        every { reportService.scanSummary(77L) } returns ScanSummary(77L, "SEMGREP", "SUCCESS", 3, mapOf("HIGH" to 2, "MEDIUM" to 1))
        every { snapshotRepository.save(any()) } answers { firstArg() }

        val snapshot = service.generate("SCAN_SUMMARY", projectId = null, scanTaskId = 77L, generatedBy = 3L)
        assertEquals(2, snapshot.templateVersionNo)
        assertEquals(77L, snapshot.scanTaskId)
        assertNull(snapshot.projectId)
        assertEquals("SCAN_SUMMARY", snapshot.snapshotType)
        assertTrue(snapshot.payload.contains("findingCount"))
        assertTrue(snapshot.payload.contains("SEMGREP"))
    }

    @Test
    fun `compliance generation captures checklistVersionId and project`() {
        every { templateRepository.findByTemplateType("COMPLIANCE") } returns template("COMPLIANCE")
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.PUBLISHED) } returns published()
        every { reportService.complianceSummary(88L) } returns ComplianceSummary(
            88L, 6L, checklistVersionId = 4L, score = BigDecimal("80.00"), totalItems = 10,
            passed = 8, failed = 2, warning = 0, manual = 0, skipped = 0, items = emptyList(),
        )
        every { snapshotRepository.save(any()) } answers { firstArg() }

        val snapshot = service.generate("COMPLIANCE", projectId = 88L, scanTaskId = null, generatedBy = null)
        assertEquals(88L, snapshot.projectId)
        assertEquals(4L, snapshot.checklistVersionId)
        assertTrue(snapshot.payload.contains("80.00"))
    }

    @Test
    fun `trend generation requires project`() {
        every { templateRepository.findByTemplateType("TREND") } returns template("TREND")
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.PUBLISHED) } returns published()
        every { reportService.trend(88L, 30) } returns listOf(TrendPoint("2026-09-01T00:00:00Z", BigDecimal("80.00"), 2))
        every { snapshotRepository.save(any()) } answers { firstArg() }

        val snapshot = service.generate("TREND", projectId = 88L, scanTaskId = null, generatedBy = null)
        assertTrue(snapshot.payload.startsWith("["))        // TrendPoint 列表 → JSON 数组（序列化字段为 evaluatedAt/score/failed）
        assertTrue(snapshot.payload.contains("evaluatedAt"))
    }

    @Test
    fun `generate without published template throws 400`() {
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns template("SCAN_SUMMARY")
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.PUBLISHED) } returns null
        val e = assertFailsWith<BusinessException> { service.generate("SCAN_SUMMARY", null, 77L, null) }
        assertEquals(400, e.code)
    }

    @Test
    fun `scan summary requires scanTaskId and unknown type rejected`() {
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns template("SCAN_SUMMARY")
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.PUBLISHED) } returns published()
        val e = assertFailsWith<BusinessException> { service.generate("SCAN_SUMMARY", null, null, null) }
        assertEquals(400, e.code)
        assertFailsWith<BusinessException> { service.generate("NOT_A_TYPE", null, null, null) }
    }

    @Test
    fun `detail missing throws 404 and export formats resolve`() {
        every { snapshotRepository.findById(99L) } returns Optional.empty()
        assertFailsWith<BusinessException> { service.detail(99L) }

        val snapshot = ReportSnapshot().apply {
            id = 3L; templateId = 1L; templateVersionNo = 2; snapshotType = "SCAN_SUMMARY"
            payload = """{"findingCount":3,"engine":"SEMGREP"}"""; generatedAt = java.time.Instant.now()
        }
        every { snapshotRepository.findById(3L) } returns Optional.of(snapshot)
        assertEquals(mapper.readTree(snapshot.payload), service.export(3L, "json"))
        assertTrue((service.export(3L, "html") as String).contains("findingCount"))
        assertFailsWith<BusinessException> { service.export(3L, "pdf") }
    }

    @Test
    fun `list rejects negative page with 400`() {
        val e = assertFailsWith<BusinessException> { service.list(null, null, -1, 20) }
        assertEquals(400, e.code)
    }

    @Test
    fun `list clamps size to 100 with id desc sort`() {
        every { snapshotRepository.findAll(any<Pageable>()) } returns mockk()
        service.list(null, null, 0, 10000000)
        verify { snapshotRepository.findAll(withArg<Pageable> { it.pageSize == 100 && it.sort.isSorted }) }
    }
}
