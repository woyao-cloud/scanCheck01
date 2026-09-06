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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 通知模板版本管理（镜像 ReportTemplateService）：DRAFT 编辑 → PUBLISH 生效 → DISABLE 停用。 */
@Service
class NotificationTemplateService(
    private val templateRepository: NotificationTemplateRepository,
    private val versionRepository: NotificationTemplateVersionRepository,
    private val auditService: AuditService,
) {
    private val objectMapper = ObjectMapper()

    companion object {
        /** 固定 6 通知类型（与 M17 一致；新增类型走迁移播种 + 内置回落机制）。 */
        val NOTIFICATION_TYPES = setOf(
            "SCAN_COMPLETED", "REPORT_SNAPSHOT_GENERATED", "REMEDIATION_ASSIGNED",
            "REMEDIATION_COMPLETED", "FINDING_REGRESSION", "REMEDIATION_WAIVER",
        )
    }

    private fun requireType(type: String) {
        if (type !in NOTIFICATION_TYPES) throw BusinessException(400, "unsupported notification type: $type")
    }

    @Transactional
    fun draft(type: String, name: String?, content: JsonNode): NotificationTemplateVersion {
        requireType(type)
        val template = templateRepository.findByTemplateType(type)
            ?: templateRepository.save(NotificationTemplate().apply {
                templateType = type
                this.name = name ?: type.lowercase()
            })
        if (name != null) template.name = name
        return currentDraftOrNew(template.id!!, content)
    }

    @Transactional
    fun publish(type: String): NotificationTemplateVersion {
        requireType(type)
        val template = templateRepository.findByTemplateType(type)
            ?: throw BusinessException(404, "no notification template for type: $type")
        val version = versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(template.id!!, TemplateStatus.DRAFT)
            ?: throw BusinessException(400, "no draft notification template version to publish for: $type")
        // 镜像 R-M12-7：发布前把既有 PUBLISHED 全部降级 DISABLED（线性状态机单一活跃版）
        versionRepository.findByTemplateIdOrderByVersionNoDesc(template.id!!)
            .filter { it.status == TemplateStatus.PUBLISHED }
            .forEach { it.status = TemplateStatus.DISABLED; versionRepository.save(it) }
        version.status = TemplateStatus.PUBLISHED
        val saved = versionRepository.save(version)
        auditService.record(
            "NOTIFICATION_TEMPLATE_PUBLISHED", "notification_template", 1L, "notification_template_version",
            saved.id, objectMapper.writeValueAsString(mapOf("type" to type, "versionNo" to saved.versionNo)),
        )
        return saved
    }

    @Transactional
    fun disable(type: String): NotificationTemplateVersion {
        requireType(type)
        val template = templateRepository.findByTemplateType(type)
            ?: throw BusinessException(404, "no notification template for type: $type")
        val versions = versionRepository.findByTemplateIdOrderByVersionNoDesc(template.id!!)
        val active = versions.firstOrNull { it.status == TemplateStatus.PUBLISHED }
            ?: throw BusinessException(400, "no published notification template version to disable for: $type")
        active.status = TemplateStatus.DISABLED
        return versionRepository.save(active)
    }

    @Transactional(readOnly = true)
    fun versions(type: String): List<NotificationTemplateVersion> {
        requireType(type)
        val template = templateRepository.findByTemplateType(type)
            ?: throw BusinessException(404, "no notification template for type: $type")
        return versionRepository.findByTemplateIdOrderByVersionNoDesc(template.id!!)
    }

    private fun currentDraftOrNew(templateId: Long, content: JsonNode): NotificationTemplateVersion {
        versionRepository.findFirstByTemplateIdAndStatusOrderByIdDesc(templateId, TemplateStatus.DRAFT)
            ?.let { draft ->
                draft.content = objectMapper.writeValueAsString(content)
                return versionRepository.save(draft)
            }
        val latest = versionRepository.findByTemplateIdOrderByVersionNoDesc(templateId).firstOrNull()
        val nextNo = (latest?.versionNo ?: 0) + 1
        return versionRepository.save(NotificationTemplateVersion().apply {
            this.templateId = templateId
            versionNo = nextNo
            status = TemplateStatus.DRAFT
            this.content = objectMapper.writeValueAsString(content)
        })
    }
}
