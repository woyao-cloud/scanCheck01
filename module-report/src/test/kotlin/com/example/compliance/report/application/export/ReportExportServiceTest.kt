package com.example.compliance.report.application.export

import com.example.compliance.common.audit.AuditService
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.report.domain.ReportSnapshot
import com.example.compliance.report.infrastructure.ReportSnapshotRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import kotlin.test.assertFailsWith

/** M16 (R-M16-D4)：导出服务——载入快照 → 渲染字节 → 审计 REPORT_EXPORT 留痕（含 format detail）；缺失 → 404。 */
class ReportExportServiceTest {

    private val snapshotRepo = mockk<ReportSnapshotRepository>(relaxed = true)
    private val auditService = mockk<AuditService>(relaxed = true)
    private val service = ReportExportService(snapshotRepo, auditService)

    private fun snapshot() = ReportSnapshot().apply {
        id = 3L; templateId = 1L; templateVersionNo = 2; projectId = 5L
        snapshotType = "COMPLIANCE"
        payload = """{"score":88.5,"items":[]}"""
        generatedAt = Instant.parse("2026-09-01T00:00:00Z")
    }

    @Test
    fun `exportXlsx renders bytes and records audit`() {
        every { snapshotRepo.findById(3L) } returns Optional.of(snapshot())
        val artifact = service.exportXlsx(3L, 42L)
        assertEquals("report-3-compliance.xlsx", artifact.filename)
        assertTrue(artifact.bytes.size > 500)
        verify {
            auditService.record(
                action = "REPORT_EXPORT", module = "report", userId = 42L,
                resourceType = "report_snapshot", resourceId = 3L,
                detail = match { it!!.contains("\"format\":\"xlsx\"") }, ip = null,
            )
        }
    }

    @Test
    fun `exportPdf renders bytes and records audit`() {
        every { snapshotRepo.findById(3L) } returns Optional.of(snapshot())
        val artifact = service.exportPdf(3L, 42L)
        assertEquals("report-3-compliance.pdf", artifact.filename)
        assertEquals(0x25.toByte(), artifact.bytes[0])
        verify {
            auditService.record(
                action = "REPORT_EXPORT", module = "report", userId = 42L,
                resourceType = "report_snapshot", resourceId = 3L,
                detail = match { it!!.contains("\"format\":\"pdf\"") }, ip = null,
            )
        }
    }

    @Test
    fun `missing snapshot is 404`() {
        every { snapshotRepo.findById(99L) } returns Optional.empty()
        assertFailsWith<BusinessException> { service.exportXlsx(99L, 1L) }
    }
}
