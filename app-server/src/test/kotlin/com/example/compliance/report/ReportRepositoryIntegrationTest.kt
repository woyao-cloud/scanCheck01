package com.example.compliance.report

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.report.domain.ReportSnapshot
import com.example.compliance.report.domain.ReportTemplate
import com.example.compliance.report.domain.ReportTemplateVersion
import com.example.compliance.report.infrastructure.ReportSnapshotRepository
import com.example.compliance.report.infrastructure.ReportTemplateRepository
import com.example.compliance.report.infrastructure.ReportTemplateVersionRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** M12 数据层：jsonb 绑定（Ruling #13/#25 地雷）+ 版本/快照查询。数据前缀 M12R-*（与集成测试真实类型隔离）。 */
class ReportRepositoryIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var templateRepository: ReportTemplateRepository
    @Autowired lateinit var versionRepository: ReportTemplateVersionRepository
    @Autowired lateinit var snapshotRepository: ReportSnapshotRepository

    @Test
    fun `template version sections jsonb roundtrip and published lookup`() {
        val template = templateRepository.save(ReportTemplate().apply {
            templateType = "M12R-SCAN"; name = "scan report"
        })
        val sections = """{"sections":[{"title":"Summary"},{"title":"By severity"}]}"""
        versionRepository.save(ReportTemplateVersion().apply {
            this.templateId = template.id!!; versionNo = 1; status = VersionStatus.PUBLISHED
            this.sections = sections
        })
        versionRepository.save(ReportTemplateVersion().apply {
            this.templateId = template.id!!; versionNo = 2; status = VersionStatus.DRAFT
            this.sections = """{"sections":[{"title":"Draft section"}]}"""
        })

        // jsonb String 往返（无 @JdbcTypeCode 会在 INSERT 失败 — Ruling #13/#25）
        val loaded = versionRepository.findByTemplateIdOrderByVersionNoDesc(template.id!!)
        assertEquals(2, loaded.size)
        assertEquals(2, loaded[0].versionNo)
        assertTrue(loaded[1].sections.contains("Summary"))
        // PUBLISHED 最新版查询（生成只取 PUBLISHED）
        val published = versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(template.id!!, VersionStatus.PUBLISHED)
        assertNotNull(published)
        assertEquals(1, published.versionNo)
    }

    @Test
    fun `snapshot payload jsonb roundtrip and project listing`() {
        val template = templateRepository.save(ReportTemplate().apply {
            templateType = "M12R-COMPLIANCE"; name = "compliance report"
        })
        val payload = """{"score":80.00,"totalItems":10,"failed":2}"""
        snapshotRepository.save(ReportSnapshot().apply {
            this.templateId = template.id!!; templateVersionNo = 1
            projectId = 700001L; snapshotType = "COMPLIANCE"; this.payload = payload
            generatedAt = Instant.now()
        })
        val rows = snapshotRepository.findByProjectIdOrderByIdDesc(700001L)
        assertEquals(1, rows.size)
        assertTrue(rows[0].payload.contains("80.00"))
    }
}
