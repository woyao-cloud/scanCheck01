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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 报告模板版本管理（镜像 ChecklistService.currentDraftOrNew/publish 先例）：DRAFT 编辑 → PUBLISH 生效 → DISABLE 停用。 */
@Service
class ReportTemplateService(
    private val templateRepository: ReportTemplateRepository,
    private val versionRepository: ReportTemplateVersionRepository,
    private val auditService: AuditService,
) {
    private val objectMapper = ObjectMapper()

    companion object {
        /** 支持的报告类型（生成/模板共用同一校验集）。 */
        val REPORT_TYPES = setOf("SCAN_SUMMARY", "COMPLIANCE", "TREND")
    }

    private fun requireType(type: String) {
        if (type !in REPORT_TYPES) throw BusinessException(400, "unsupported report type: $type")
    }

    @Transactional
    fun draft(type: String, name: String?, sections: JsonNode): ReportTemplateVersion {
        requireType(type)
        val template = templateRepository.findByTemplateType(type)
            ?: templateRepository.save(ReportTemplate().apply {
                this.templateType = type
                this.name = name ?: type.lowercase()
            })
        if (name != null) template.name = name
        return currentDraftOrNew(template.id!!, sections)
    }

    @Transactional
    fun publish(type: String): ReportTemplateVersion {
        requireType(type)
        val template = templateRepository.findByTemplateType(type)
            ?: throw BusinessException(404, "no report template for type: $type")
        val version = versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(template.id!!, VersionStatus.DRAFT)
            ?: throw BusinessException(400, "no draft report template version to publish for: $type")
        // R-M12-7（终审 Important #2）: 线性状态机单一活跃版——发布前把既有 PUBLISHED 全部降级为 DISABLED。
        // 否则多 PUBLISHED 并存时 disable 只停最新，生成回落旧 PUBLISHED（「停用」意图落空）。
        versionRepository.findByTemplateIdOrderByVersionNoDesc(template.id!!)
            .filter { it.status == VersionStatus.PUBLISHED }
            .forEach { it.status = VersionStatus.DISABLED; versionRepository.save(it) }
        version.status = VersionStatus.PUBLISHED
        val saved = versionRepository.save(version)
        // Ruling #34: audit_log.detail 是 JSONB，detail 必须传合法 JSON
        auditService.record(
            "REPORT_TEMPLATE_PUBLISHED", "report_template", 1L, "report_template_version",
            saved.id, objectMapper.writeValueAsString(mapOf("type" to type, "versionNo" to saved.versionNo)),
        )
        return saved
    }

    @Transactional
    fun disable(type: String): ReportTemplateVersion {
        requireType(type)
        val template = templateRepository.findByTemplateType(type)
            ?: throw BusinessException(404, "no report template for type: $type")
        val versions = versionRepository.findByTemplateIdOrderByVersionNoDesc(template.id!!)
        // R-M12-6: 停用当前活跃的 PUBLISHED 版（spec §3.2 线性状态机 DRAFT→PUBLISHED→DISABLED，
        // "生成只取 PUBLISHED"）。最新版若为打开中的 DRAFT 则无视之——DRAFT 从不参与生成，
        // 停掉 DRAFT 只会让活跃 PUBLISHED 继续被生成使用，违背「停用」意图。
        val active = versions.firstOrNull { it.status == VersionStatus.PUBLISHED }
            ?: throw BusinessException(400, "no published report template version to disable for: $type")
        active.status = VersionStatus.DISABLED
        return versionRepository.save(active)
    }

    @Transactional(readOnly = true)
    fun versions(type: String): List<ReportTemplateVersion> {
        requireType(type)
        val template = templateRepository.findByTemplateType(type)
            ?: throw BusinessException(404, "no report template for type: $type")
        return versionRepository.findByTemplateIdOrderByVersionNoDesc(template.id!!)
    }

    private fun currentDraftOrNew(templateId: Long, sections: JsonNode): ReportTemplateVersion {
        versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(templateId, VersionStatus.DRAFT)
            ?.let { draft ->
                draft.sections = objectMapper.writeValueAsString(sections)
                return versionRepository.save(draft)
            }
        val latest = versionRepository.findByTemplateIdOrderByVersionNoDesc(templateId).firstOrNull()
        val nextNo = (latest?.versionNo ?: 0) + 1
        return versionRepository.save(ReportTemplateVersion().apply {
            this.templateId = templateId
            versionNo = nextNo
            status = VersionStatus.DRAFT
            this.sections = objectMapper.writeValueAsString(sections)
        })
    }
}
