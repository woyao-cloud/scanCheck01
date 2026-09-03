package com.example.compliance.checklist.application

import com.example.compliance.checklist.domain.ChecklistVersion
import com.example.compliance.checklist.domain.VersionStatus
import com.example.compliance.checklist.infrastructure.BindingRepository
import com.example.compliance.checklist.infrastructure.ChecklistItemRepository
import com.example.compliance.checklist.infrastructure.ChecklistRepository
import com.example.compliance.checklist.infrastructure.ChecklistVersionRepository
import com.example.compliance.checklist.infrastructure.StandardRepository
import com.example.compliance.common.audit.AuditService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/** M9：清单写操作审计（审计/回滚闭环的写侧）。 */
class ChecklistAuditTest {
    private val standardRepository = mockk<StandardRepository>(relaxed = true)
    private val checklistRepository = mockk<ChecklistRepository>(relaxed = true)
    private val versionRepository = mockk<ChecklistVersionRepository>(relaxed = true)
    private val itemRepository = mockk<ChecklistItemRepository>(relaxed = true)
    private val bindingRepository = mockk<BindingRepository>(relaxed = true)
    private val auditService = mockk<AuditService>(relaxed = true)
    private val service = ChecklistService(
        standardRepository, checklistRepository, versionRepository, itemRepository, bindingRepository, auditService,
    )

    @Test
    fun `publish writes audit record`() {
        every { versionRepository.findFirstByChecklistIdAndStatusOrderByIdDesc(any(), VersionStatus.DRAFT) } returns
            ChecklistVersion().apply { id = 1L; versionNo = "V1"; status = VersionStatus.DRAFT }
        every { itemRepository.findByVersionId(any()) } returns emptyList()
        every { versionRepository.save(any()) } answers { firstArg<ChecklistVersion>() }

        service.publish(1L)

        // PF-9 真实签名：record(action, module, userId, resourceType, resourceId, detail, ip)
        verify { auditService.record("CHECKLIST_PUBLISHED", "checklist", 1L, "checklist_version", any(), any()) }
    }
}
