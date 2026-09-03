package com.example.compliance.checklist.application

import com.example.compliance.checklist.domain.ChecklistItem
import com.example.compliance.checklist.domain.ChecklistVersion
import com.example.compliance.checklist.domain.ComplianceChecklist
import com.example.compliance.checklist.domain.ComplianceStandard
import com.example.compliance.checklist.domain.ProjectChecklistBinding
import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.checklist.infrastructure.BindingRepository
import com.example.compliance.checklist.infrastructure.ChecklistItemRepository
import com.example.compliance.checklist.infrastructure.ChecklistRepository
import com.example.compliance.checklist.infrastructure.ChecklistVersionRepository
import com.example.compliance.checklist.infrastructure.StandardRepository
import com.example.compliance.common.audit.AuditService
import com.example.compliance.common.exception.BusinessException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class ChecklistService(
    private val standardRepository: StandardRepository,
    private val checklistRepository: ChecklistRepository,
    private val versionRepository: ChecklistVersionRepository,
    private val itemRepository: ChecklistItemRepository,
    private val bindingRepository: BindingRepository,
    private val auditService: AuditService,
) {
    private val objectMapper = ObjectMapper()

    @Transactional
    fun createStandard(code: String, name: String, description: String?): ComplianceStandard {
        if (standardRepository.existsByCode(code)) {
            throw BusinessException(400, "standard code already exists: $code")
        }
        val saved = standardRepository.save(ComplianceStandard().apply {
            this.code = code; this.name = name; this.description = description
        })
        auditService.record(
            "CHECKLIST_CREATED", "checklist", 1L, "checklist_version",
            saved.id, """{"type":"standard","code":"$code","name":"$name"}""",
        )
        return saved
    }

    @Transactional
    fun createChecklist(standardId: Long, code: String, name: String): ComplianceChecklist {
        if (!standardRepository.existsById(standardId)) {
            throw BusinessException(404, "standard not found: $standardId")
        }
        if (checklistRepository.existsByCode(code)) {
            throw BusinessException(400, "checklist code already exists: $code")
        }
        val checklist = checklistRepository.save(ComplianceChecklist().apply {
            this.standardId = standardId; this.code = code; this.name = name
        })
        val version = versionRepository.save(ChecklistVersion().apply {
            checklistId = checklist.id!!; versionNo = "V1"; status = VersionStatus.DRAFT
        })
        auditService.record(
            "CHECKLIST_CREATED", "checklist", 1L, "checklist_version",
            version.id, """{"type":"checklist","code":"$code","name":"$name","versionNo":"${version.versionNo}"}""",
        )
        return checklist
    }

    @Transactional
    fun addItem(checklistId: Long, command: AddItemCommand): ChecklistItem {
        val version = currentDraftOrNew(checklistId)
        val saved = itemRepository.save(ChecklistItem().apply {
            versionId = version.id!!
            itemCode = command.itemCode
            name = command.name
            category = command.category
            riskLevel = command.riskLevel
            description = command.description
            basis = command.basis
            remediation = command.remediation
            required = command.required
            waivable = command.waivable
            scoreWeight = command.scoreWeight
        })
        auditService.record(
            "CHECKLIST_ITEM_ADDED", "checklist", 1L, "checklist_version",
            version.id!!, """{"itemCode":"${saved.itemCode}","versionId":${version.id!!}}""",
        )
        return saved
    }

    /** 返回当前 DRAFT 版本；若最新已是 PUBLISHED 则新建下一个版本号（版本化编辑）。 */
    private fun currentDraftOrNew(checklistId: Long): ChecklistVersion {
        checklistRepository.findById(checklistId)
            .orElseThrow { BusinessException(404, "checklist not found: $checklistId") }
        versionRepository.findFirstByChecklistIdAndStatusOrderByIdDesc(checklistId, VersionStatus.DRAFT)
            ?.let { return it }
        val latest = versionRepository.findByChecklistIdOrderByVersionNoDesc(checklistId).firstOrNull()
        val nextNo = latest?.versionNo?.removePrefix("V")?.toIntOrNull()?.plus(1)?.let { "V$it" } ?: "V1"
        return versionRepository.save(ChecklistVersion().apply {
            this.checklistId = checklistId; versionNo = nextNo; status = VersionStatus.DRAFT
        })
    }

    @Transactional
    fun publish(checklistId: Long): ChecklistVersion {
        val version = versionRepository.findFirstByChecklistIdAndStatusOrderByIdDesc(checklistId, VersionStatus.DRAFT)
            ?: throw BusinessException(400, "no draft version to publish for checklist: $checklistId")
        val items = itemRepository.findByVersionId(version.id!!)
        val snapshot = objectMapper.writeValueAsString(
            items.map { mapOf("itemCode" to it.itemCode, "name" to it.name, "riskLevel" to it.riskLevel) }
        )
        version.status = VersionStatus.PUBLISHED
        version.contentSnapshot = snapshot
        version.publishedAt = Instant.now()
        val saved = versionRepository.save(version)
        auditService.record(
            "CHECKLIST_PUBLISHED", "checklist", 1L, "checklist_version",
            // Ruling #34: audit_log.detail is JSONB (V1 DDL) — detail must be valid JSON.
            saved.id, """{"checklist":$checklistId,"version":"${saved.versionNo}"}""",
        )
        return saved
    }

    @Transactional
    fun bindProject(projectId: Long, checklistVersionId: Long): ProjectChecklistBinding {
        val version = versionRepository.findById(checklistVersionId)
            .orElseThrow { BusinessException(404, "version not found: $checklistVersionId") }
        if (version.status != VersionStatus.PUBLISHED) {
            throw BusinessException(400, "only published version can be bound")
        }
        val binding = bindingRepository.save(ProjectChecklistBinding().apply {
            this.projectId = projectId; this.checklistVersionId = checklistVersionId
        })
        auditService.record(
            "CHECKLIST_BIND", "checklist", 1L, "project",
            projectId, """{"checklistVersion":$checklistVersionId}""",
        )
        return binding
    }
}
