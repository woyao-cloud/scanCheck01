package com.example.compliance.checklist.application

import com.example.compliance.checklist.domain.ChecklistItem
import com.example.compliance.checklist.domain.ChecklistVersion
import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.checklist.infrastructure.BindingRepository
import com.example.compliance.checklist.infrastructure.ChecklistItemRepository
import com.example.compliance.checklist.infrastructure.ChecklistVersionRepository
import org.springframework.stereotype.Service

@Service
class ChecklistQueryService(
    private val itemRepository: ChecklistItemRepository,
    private val bindingRepository: BindingRepository,
    private val versionRepository: ChecklistVersionRepository,
) {
    fun versionItems(versionId: Long): List<ChecklistItem> = itemRepository.findByVersionId(versionId)

    /** 清单的全部版本（版本化配置可审计，供 GET /compliance/checklists/{id}/versions）。 */
    fun versions(checklistId: Long): List<ChecklistVersion> =
        versionRepository.findByChecklistIdOrderByVersionNoDesc(checklistId)

    /** 项目当前绑定的已发布版本的全部合规项；未绑定返回 null。 */
    fun publishedItemsForProject(projectId: Long): List<ChecklistItem>? {
        val binding = bindingRepository.findFirstByProjectIdOrderByIdDesc(projectId) ?: return null
        val version = versionRepository.findById(binding.checklistVersionId).orElse(null) ?: return null
        if (version.status != VersionStatus.PUBLISHED) return null
        return itemRepository.findByVersionId(version.id!!)
    }

    /** 项目当前绑定的已发布版本；未绑定或未发布返回 null。M6 版本盖章使用。 */
    fun publishedVersionForProject(projectId: Long): ChecklistVersion? {
        val binding = bindingRepository.findFirstByProjectIdOrderByIdDesc(projectId) ?: return null
        val version = versionRepository.findById(binding.checklistVersionId).orElse(null) ?: return null
        if (version.status != VersionStatus.PUBLISHED) return null
        return version
    }
}
