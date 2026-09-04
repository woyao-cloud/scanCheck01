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
        val latest = versions.firstOrNull()
            ?: throw BusinessException(400, "no report template version to disable for: $type")
        if (latest.status == VersionStatus.DISABLED) throw BusinessException(400, "report template already disabled: $type")
        latest.status = VersionStatus.DISABLED
        return versionRepository.save(latest)
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
