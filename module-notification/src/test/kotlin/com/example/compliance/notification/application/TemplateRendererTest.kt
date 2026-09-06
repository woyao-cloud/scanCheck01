package com.example.compliance.notification.application

import com.example.compliance.notification.domain.NotificationTemplate
import com.example.compliance.notification.domain.NotificationTemplateVersion
import com.example.compliance.notification.domain.TemplateStatus
import com.example.compliance.notification.infrastructure.NotificationTemplateRepository
import com.example.compliance.notification.infrastructure.NotificationTemplateVersionRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TemplateRendererTest {
    private val templateRepository = mockk<NotificationTemplateRepository>()
    private val versionRepository = mockk<NotificationTemplateVersionRepository>()
    private val renderer = TemplateRenderer(templateRepository, versionRepository)

    private fun published(id: Long = 2L, content: String): NotificationTemplateVersion =
        NotificationTemplateVersion().apply {
            templateId = 1L; versionNo = id.toInt(); status = TemplateStatus.PUBLISHED; this.content = content
        }

    @Test
    fun `missing published falls back to built-in defaults`() {
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns null
        val (title, body) = renderer.render("SCAN_COMPLETED", mapOf("scanTaskId" to 1L, "status" to "SUCCESS"))
        assertEquals("scan completed", title)
        assertEquals("扫描 1 完成：SUCCESS", body)   // 中文正文占位符替换
    }

    @Test
    fun `missing variable is replaced with empty string`() {
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns null
        val (_, body) = renderer.render("SCAN_COMPLETED", emptyMap())
        assertEquals("扫描  完成：", body)   // {scanTaskId}/{status} 缺失键均空串（R-M18-8）
    }

    @Test
    fun `null valued variable is replaced with empty string`() {
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns null
        val (_, body) = renderer.render("SCAN_COMPLETED", mapOf("scanTaskId" to null, "status" to null))
        assertEquals("扫描  完成：", body)   // 存在键但值为 null → 空串
    }

    @Test
    fun `unknown type falls back to the type itself`() {
        every { templateRepository.findByTemplateType("UNKNOWN") } returns null
        val (title, body) = renderer.render("UNKNOWN", emptyMap())
        assertEquals("UNKNOWN", title)
        assertEquals("UNKNOWN", body)   // R-M18-8 退化分支
    }

    @Test
    fun `published version content is rendered`() {
        val template = NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED" }
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, TemplateStatus.PUBLISHED) } returns
            published(content = """{"title":"custom t","body":"任务 {scanTaskId} 完成"}""")
        val (title, body) = renderer.render("SCAN_COMPLETED", mapOf("scanTaskId" to 5L))
        assertEquals("custom t", title)
        assertEquals("任务 5 完成", body)
    }

    @Test
    fun `content json parse failure falls back to built-in`() {
        val template = NotificationTemplate().apply { id = 1L; templateType = "SCAN_COMPLETED" }
        every { templateRepository.findByTemplateType("SCAN_COMPLETED") } returns template
        every { versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(1L, TemplateStatus.PUBLISHED) } returns
            published(content = "not json")
        val (title, body) = renderer.render("SCAN_COMPLETED", mapOf("scanTaskId" to 1L, "status" to "SUCCESS"))
        assertEquals("scan completed", title)
        assertEquals("扫描 1 完成：SUCCESS", body)   // R-M18-7：解析失败绝不抛，回落内置
    }
}
