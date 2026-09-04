package com.example.compliance.report.application

import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.common.audit.AuditService
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.report.domain.ReportTemplate
import com.example.compliance.report.domain.ReportTemplateVersion
import com.example.compliance.report.infrastructure.ReportTemplateRepository
import com.example.compliance.report.infrastructure.ReportTemplateVersionRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReportTemplateServiceTest {
    private val templateRepository = mockk<ReportTemplateRepository>(relaxed = true)
    private val versionRepository = mockk<ReportTemplateVersionRepository>(relaxed = true)
    private val auditService = mockk<AuditService>(relaxed = true)
    private val service = ReportTemplateService(templateRepository, versionRepository, auditService)
    private val mapper = ObjectMapper()

    private fun sections(s: String): JsonNode = mapper.readTree(s)

    @Test
    fun `first draft creates template line and version V1`() {
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns null
        // save 桩必须给模板赋 id —— service 用 template.id!! 进 currentDraftOrNew，不设会 NPE
        every { templateRepository.save(any()) } answers { firstArg<ReportTemplate>().also { it.id = 42L } }
        // MockK relaxed 对 nullable 返回类型会返回 child mock 而非 null —— 必须显式桩 null
        // （与 `draft after publish opens next version` 用例一致）
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(42L, VersionStatus.DRAFT) } returns null
        every { versionRepository.save(any()) } answers { firstArg() }
        val version = service.draft("SCAN_SUMMARY", "scan report", sections("""{"sections":[{"title":"Summary"}]}"""))

        verify { templateRepository.save(any()) }
        assertEquals(1, version.versionNo)
        assertEquals(VersionStatus.DRAFT, version.status)
        assertTrue(version.sections.contains("Summary"))
    }

    @Test
    fun `redraft updates existing draft version instead of opening new one`() {
        val template = ReportTemplate().apply { id = 1L; templateType = "COMPLIANCE"; name = "c" }
        val draft = ReportTemplateVersion().apply { id = 5L; templateId = 1L; versionNo = 1; status = VersionStatus.DRAFT; sections = "{}" }
        every { templateRepository.findByTemplateType("COMPLIANCE") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.DRAFT) } returns draft
        every { versionRepository.save(any()) } answers { firstArg() }

        val updated = service.draft("COMPLIANCE", null, sections("""{"sections":[{"title":"New"}]}"""))
        assertEquals(1, updated.versionNo)
        assertTrue(updated.sections.contains("New"))
    }

    @Test
    fun `draft after publish opens next version`() {
        val template = ReportTemplate().apply { id = 1L; templateType = "TREND"; name = "t" }
        val published = ReportTemplateVersion().apply { id = 9L; templateId = 1L; versionNo = 1; status = VersionStatus.PUBLISHED; sections = "{}" }
        every { templateRepository.findByTemplateType("TREND") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.DRAFT) } returns null
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(published)
        every { versionRepository.save(any()) } answers { firstArg() }

        val version = service.draft("TREND", null, sections("{}"))
        assertEquals(2, version.versionNo)
        assertEquals(VersionStatus.DRAFT, version.status)
    }

    @Test
    fun `publish requires an existing draft and records audit with valid json detail`() {
        val template = ReportTemplate().apply { id = 1L; templateType = "SCAN_SUMMARY"; name = "s" }
        val draft = ReportTemplateVersion().apply { id = 5L; templateId = 1L; versionNo = 1; status = VersionStatus.DRAFT; sections = "{}" }
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.DRAFT) } returns draft
        every { versionRepository.save(any()) } answers { firstArg() }

        val published = service.publish("SCAN_SUMMARY")
        assertEquals(VersionStatus.PUBLISHED, published.status)
        // Ruling #34: audit detail 必须是合法 JSON —— match matcher 实际解析校验，而非仅证明调用过
        verify {
            auditService.record(
                "REPORT_TEMPLATE_PUBLISHED", "report_template", 1L, "report_template_version", 5L,
                match { runCatching { mapper.readTree(it) }.isSuccess },
            )
        }
    }

    @Test
    fun `publish without draft throws 400`() {
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns
            ReportTemplate().apply { id = 1L; templateType = "SCAN_SUMMARY"; name = "s" }
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.DRAFT) } returns null
        val e = assertFailsWith<BusinessException> { service.publish("SCAN_SUMMARY") }
        assertEquals(400, e.code)
    }

    @Test
    fun `publish demotes prior published version - single active linear machine`() {
        // 终审 Important #2: draft v1→publish→draft v2→publish 后 v1 必须降级为 DISABLED，
        // 否则 disable 只停 v2 后生成回落 v1（「停用」意图落空）
        val template = ReportTemplate().apply { id = 1L; templateType = "SCAN_SUMMARY"; name = "s" }
        val v1 = ReportTemplateVersion().apply { id = 5L; templateId = 1L; versionNo = 1; status = VersionStatus.PUBLISHED; sections = "{}" }
        val draft2 = ReportTemplateVersion().apply { id = 6L; templateId = 1L; versionNo = 2; status = VersionStatus.DRAFT; sections = "{}" }
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, VersionStatus.DRAFT) } returns draft2
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(draft2, v1)
        every { versionRepository.save(any()) } answers { firstArg() }

        val published = service.publish("SCAN_SUMMARY")
        assertEquals(VersionStatus.PUBLISHED, published.status)
        assertEquals(VersionStatus.DISABLED, v1.status)   // 旧 PUBLISHED 被降级，v2 成为唯一活跃版
        assertEquals(2, published.versionNo)
    }

    @Test
    fun `disable marks latest published version disabled`() {
        val template = ReportTemplate().apply { id = 1L; templateType = "SCAN_SUMMARY"; name = "s" }
        val published = ReportTemplateVersion().apply { id = 5L; templateId = 1L; versionNo = 2; status = VersionStatus.PUBLISHED; sections = "{}" }
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns template
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(published)
        every { versionRepository.save(any()) } answers { firstArg() }

        val disabled = service.disable("SCAN_SUMMARY")
        assertEquals(VersionStatus.DISABLED, disabled.status)
    }

    @Test
    fun `disable ignores open draft and targets active published version`() {
        // R-M12-6: 打开中的 DRAFT v3 在顶部，disable 必须无视之并停用活跃 PUBLISHED v2
        val template = ReportTemplate().apply { id = 1L; templateType = "SCAN_SUMMARY"; name = "s" }
        val draft = ReportTemplateVersion().apply { id = 9L; templateId = 1L; versionNo = 3; status = VersionStatus.DRAFT; sections = "{}" }
        val published = ReportTemplateVersion().apply { id = 8L; templateId = 1L; versionNo = 2; status = VersionStatus.PUBLISHED; sections = "{}" }
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns template
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(draft, published)
        every { versionRepository.save(any()) } answers { firstArg() }

        val disabled = service.disable("SCAN_SUMMARY")
        assertEquals(VersionStatus.DISABLED, disabled.status)
        assertEquals(2, disabled.versionNo)   // 目标是 PUBLISHED v2，不是 DRAFT v3
    }

    @Test
    fun `disable without published version throws 400`() {
        // R-M12-6: 仅 DRAFT（从未发布）→ 无活跃 PUBLISHED 可停用 → 400
        val template = ReportTemplate().apply { id = 1L; templateType = "SCAN_SUMMARY"; name = "s" }
        val draft = ReportTemplateVersion().apply { id = 9L; templateId = 1L; versionNo = 1; status = VersionStatus.DRAFT; sections = "{}" }
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns template
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(draft)
        val e = assertFailsWith<BusinessException> { service.disable("SCAN_SUMMARY") }
        assertEquals(400, e.code)
    }

    @Test
    fun `versions lists desc`() {
        every { templateRepository.findByTemplateType("SCAN_SUMMARY") } returns
            ReportTemplate().apply { id = 1L; templateType = "SCAN_SUMMARY"; name = "s" }
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns
            listOf(
                ReportTemplateVersion().apply { id = 9L; templateId = 1L; versionNo = 2; status = VersionStatus.DRAFT; sections = "{}" },
                ReportTemplateVersion().apply { id = 8L; templateId = 1L; versionNo = 1; status = VersionStatus.PUBLISHED; sections = "{}" },
            )
        val versions = service.versions("SCAN_SUMMARY")
        assertEquals(2, versions[0].versionNo)
        assertEquals(1, versions[1].versionNo)
    }

    @Test
    fun `draft with unknown type rejects`() {
        assertFailsWith<BusinessException> {
            service.draft("NOT_A_TYPE", null, sections("{}"))
        }
    }
}
