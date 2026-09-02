package com.example.compliance.checklist

import com.example.compliance.AbstractIntegrationTest
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
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals

class ChecklistRepositoryIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var standardRepository: StandardRepository
    @Autowired lateinit var checklistRepository: ChecklistRepository
    @Autowired lateinit var versionRepository: ChecklistVersionRepository
    @Autowired lateinit var itemRepository: ChecklistItemRepository
    @Autowired lateinit var bindingRepository: BindingRepository

    @Test
    fun `standard checklist version item binding persist`() {
        val standard = standardRepository.save(ComplianceStandard().apply {
            code = "SEC"; name = "安全编码规范"
        })
        val checklist = checklistRepository.save(ComplianceChecklist().apply {
            standardId = standard.id!!; code = "SEC-BASIC"; name = "安全基线清单"
        })
        val version = versionRepository.save(ChecklistVersion().apply {
            checklistId = checklist.id!!; versionNo = "V1"; status = VersionStatus.DRAFT
        })
        itemRepository.save(ChecklistItem().apply {
            versionId = version.id!!; itemCode = "SEC-001"; name = "禁止SQL注入"; riskLevel = "HIGH"
        })
        bindingRepository.save(ProjectChecklistBinding().apply {
            projectId = 1L; checklistVersionId = version.id!!
        })

        assertEquals(1, itemRepository.findByVersionId(version.id!!).size)
        assertEquals(1, versionRepository.findByChecklistIdOrderByVersionNoDesc(checklist.id!!).size)
        assertEquals(version.id, bindingRepository.findFirstByProjectIdOrderByIdDesc(1L)?.checklistVersionId)
    }
}
