package com.example.compliance.report.application

import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.report.domain.ReportSnapshot
import com.example.compliance.report.infrastructure.ReportSnapshotRepository
import com.example.compliance.report.infrastructure.ReportTemplateRepository
import com.example.compliance.report.infrastructure.ReportTemplateVersionRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 报告快照生成与查询（spec P3-D3/P3-D4）：生成只取 PUBLISHED 最新模板版，payload 经 ReportService+ReportMetrics 统一口径后落 JSONB。 */
@Service
class ReportGenerationService(
    private val reportService: ReportService,
    private val templateRepository: ReportTemplateRepository,
    private val versionRepository: ReportTemplateVersionRepository,
    private val snapshotRepository: ReportSnapshotRepository,
) {
    private val objectMapper = ObjectMapper()

    @Transactional
    fun generate(type: String, projectId: Long?, scanTaskId: Long?, generatedBy: Long?): ReportSnapshot {
        if (type !in ReportTemplateService.REPORT_TYPES) throw BusinessException(400, "unsupported report type: $type")
        if (type == "SCAN_SUMMARY" && scanTaskId == null) throw BusinessException(400, "SCAN_SUMMARY requires scanTaskId")
        if (type != "SCAN_SUMMARY" && projectId == null) throw BusinessException(400, "$type requires projectId")

        val template = templateRepository.findByTemplateType(type)
            ?: throw BusinessException(404, "no report template for type: $type")
        val version = versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(template.id!!, VersionStatus.PUBLISHED)
            ?: throw BusinessException(400, "no published report template for type: $type")

        val checklistVersionId: Long?
        val payload: String
        when (type) {
            "SCAN_SUMMARY" -> {
                checklistVersionId = null
                payload = objectMapper.writeValueAsString(reportService.scanSummary(scanTaskId!!))
            }
            "COMPLIANCE" -> {
                val summary = reportService.complianceSummary(projectId!!)
                checklistVersionId = summary.checklistVersionId
                payload = objectMapper.writeValueAsString(summary)
            }
            else -> { // TREND
                checklistVersionId = null
                payload = objectMapper.writeValueAsString(reportService.trend(projectId!!, 30))
            }
        }
        return snapshotRepository.save(ReportSnapshot().apply {
            this.templateId = template.id!!
            templateVersionNo = version.versionNo
            this.projectId = projectId
            this.scanTaskId = scanTaskId
            this.checklistVersionId = checklistVersionId
            this.snapshotType = type
            this.payload = payload
            this.generatedBy = generatedBy
            this.generatedAt = Instant.now()
        })
    }

    @Transactional(readOnly = true)
    fun detail(id: Long): ReportSnapshot = snapshotRepository.findById(id)
        .orElseThrow { BusinessException(404, "report snapshot not found: $id") }

    @Transactional(readOnly = true)
    fun export(id: Long, format: String): Any {
        val snapshot = detail(id)
        return when (format) {
            "json" -> objectMapper.readTree(snapshot.payload)
            "html" -> HtmlReportRenderer.render(snapshot)
            else -> throw BusinessException(400, "unsupported export format: $format")
        }
    }

    // 列表：projectId/type 均为可选过滤，4 分支（快照列表页，无需 Specification——YAGNI）
    @Transactional(readOnly = true)
    fun list(projectId: Long?, type: String?, page: Int, size: Int): Page<ReportSnapshot> {
        // C2（终审）: 公开列表端点硬化 —— 负 page 拒绝为 400，size 钳制 [1,100]，固定 id 倒序保证分页确定性
        if (page < 0) throw BusinessException(400, "page must be non-negative")
        val pageable = PageRequest.of(page, size.coerceIn(1, 100), Sort.by(Sort.Direction.DESC, "id"))
        return when {
            projectId != null && type != null -> snapshotRepository.findByProjectIdAndSnapshotType(projectId, type, pageable)
            projectId != null -> snapshotRepository.findByProjectId(projectId, pageable)
            type != null -> snapshotRepository.findBySnapshotType(type, pageable)
            else -> snapshotRepository.findAll(pageable)
        }
    }
}
