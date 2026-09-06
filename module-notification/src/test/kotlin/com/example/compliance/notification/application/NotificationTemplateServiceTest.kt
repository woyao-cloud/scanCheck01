package com.example.compliance.notification.application

import com.example.compliance.common.audit.AuditService
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.notification.domain.NotificationTemplate
import com.example.compliance.notification.domain.NotificationTemplateVersion
import com.example.compliance.notification.domain.TemplateStatus
import com.example.compliance.notification.infrastructure.NotificationTemplateRepository
import com.example.compliance.notification.infrastructure.NotificationTemplateVersionRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NotificationTemplateServiceTest {
    private val templateRepository = mockk<NotificationTemplateRepository>(relaxed = true)
    private val versionRepository = mockk<NotificationTemplateVersionRepository>(relaxed = true)
    private val auditService = mockk<AuditService>(relaxed = true)
    private val service = NotificationTemplateService(templateRepository, versionRepository, auditService)
    private val mapper = ObjectMapper()

    private fun content(s: String): JsonNode = mapper.readTree(s)
    private fun version(id: Long, templateId: Long = 1L, versionNo: Int, status: TemplateStatus, body: String = "{}") =
        NotificationTemplateVersion().apply { this.id = id; this.templateId = templateId; this.versionNo = versionNo; this.status = status; this.content = body }

    @Test
    fun `first draft creates template line and version V1`() {
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns null
        // save 桩必须给模板赋 id —— service 用 template.id!! 进 currentDraftOrNew，不设会 NPE
        every { templateRepository.save(any()) } answers { firstArg<NotificationTemplate>().also { it.id = 42L } }
        // MockK relaxed 对 nullable 返回类型会返回 child mock 而非 null —— 必须显式桩 null
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(42L, TemplateStatus.DRAFT) } returns null
        every { versionRepository.save(any()) } answers { firstArg() }
        val v = service.draft("SCAN_COMPLETED", "scan", content("""{"title":"t","body":"b"}"""))

        verify { templateRepository.save(any()) }
        assertEquals(1, v.versionNo)
        assertEquals(TemplateStatus.DRAFT, v.status)
        assertTrue(v.content.contains("\"title\":\"t\""))
    }

    @Test
    fun `redraft updates existing draft version instead of opening new one`() {
        val template = NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED"; name = "c" }
        val draft = version(5L, versionNo = 1, status = TemplateStatus.DRAFT)
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, TemplateStatus.DRAFT) } returns draft
        every { versionRepository.save(any()) } answers { firstArg() }

        val updated = service.draft("SCAN_COMPLETED", null, content("""{"title":"t","body":"New"}"""))
        assertEquals(1, updated.versionNo)
        assertTrue(updated.content.contains("New"))
    }

    @Test
    fun `draft after publish opens next version`() {
        val template = NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED"; name = "t" }
        val published = version(9L, versionNo = 1, status = TemplateStatus.PUBLISHED)
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, TemplateStatus.DRAFT) } returns null
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(published)
        every { versionRepository.save(any()) } answers { firstArg() }

        val v = service.draft("SCAN_COMPLETED", null, content("{}"))
        assertEquals(2, v.versionNo)
        assertEquals(TemplateStatus.DRAFT, v.status)
    }

    @Test
    fun `publish requires an existing draft and records audit with valid json detail`() {
        val template = NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED"; name = "s" }
        val draft = version(5L, versionNo = 1, status = TemplateStatus.DRAFT)
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, TemplateStatus.DRAFT) } returns draft
        every { versionRepository.save(any()) } answers { firstArg() }

        val published = service.publish("SCAN_COMPLETED")
        assertEquals(TemplateStatus.PUBLISHED, published.status)
        // audit detail 必须是合法 JSON —— match matcher 实际解析校验，而非仅证明调用过
        verify {
            auditService.record(
                "NOTIFICATION_TEMPLATE_PUBLISHED", "notification_template", 1L, "notification_template_version", 5L,
                match { runCatching { mapper.readTree(it) }.isSuccess },
            )
        }
    }

    @Test
    fun `publish without draft throws 400`() {
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns
            NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED"; name = "s" }
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, TemplateStatus.DRAFT) } returns null
        val e = assertFailsWith<BusinessException> { service.publish("SCAN_COMPLETED") }
        assertEquals(400, e.code)
    }

    @Test
    fun `publish demotes prior published version - single active linear machine`() {
        val template = NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED"; name = "s" }
        val v1 = version(5L, versionNo = 1, status = TemplateStatus.PUBLISHED)
        val draft2 = version(6L, versionNo = 2, status = TemplateStatus.DRAFT)
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, TemplateStatus.DRAFT) } returns draft2
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(draft2, v1)
        every { versionRepository.save(any()) } answers { firstArg() }

        val published = service.publish("SCAN_COMPLETED")
        assertEquals(TemplateStatus.PUBLISHED, published.status)
        assertEquals(TemplateStatus.DISABLED, v1.status)   // 旧 PUBLISHED 被降级，v2 成为唯一活跃版
        assertEquals(2, published.versionNo)
    }

    @Test
    fun `disable marks latest published version disabled`() {
        val template = NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED"; name = "s" }
        val published = version(5L, versionNo = 2, status = TemplateStatus.PUBLISHED)
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns template
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(published)
        every { versionRepository.save(any()) } answers { firstArg() }

        val disabled = service.disable("SCAN_COMPLETED")
        assertEquals(TemplateStatus.DISABLED, disabled.status)
    }

    @Test
    fun `disable ignores open draft and targets active published version`() {
        val template = NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED"; name = "s" }
        val draft = version(9L, versionNo = 3, status = TemplateStatus.DRAFT)
        val published = version(8L, versionNo = 2, status = TemplateStatus.PUBLISHED)
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns template
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(draft, published)
        every { versionRepository.save(any()) } answers { firstArg() }

        val disabled = service.disable("SCAN_COMPLETED")
        assertEquals(TemplateStatus.DISABLED, disabled.status)
        assertEquals(2, disabled.versionNo)   // 目标是 PUBLISHED v2，不是 DRAFT v3
    }

    @Test
    fun `disable without published version throws 400`() {
        val template = NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED"; name = "s" }
        val draft = version(9L, versionNo = 1, status = TemplateStatus.DRAFT)
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns template
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns listOf(draft)
        val e = assertFailsWith<BusinessException> { service.disable("SCAN_COMPLETED") }
        assertEquals(400, e.code)
    }

    @Test
    fun `versions lists desc`() {
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns
            NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED"; name = "s" }
        every { versionRepository.findByTemplateIdOrderByVersionNoDesc(1L) } returns
            listOf(version(9L, versionNo = 2, status = TemplateStatus.DRAFT), version(8L, versionNo = 1, status = TemplateStatus.PUBLISHED))
        val versions = service.versions("SCAN_COMPLETED")
        assertEquals(2, versions[0].versionNo)
        assertEquals(1, versions[1].versionNo)
    }

    @Test
    fun `draft with unknown type rejects`() {
        assertFailsWith<BusinessException> {
            service.draft("NOT_A_TYPE", null, content("{}"))
        }
    }
}
