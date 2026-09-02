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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ChecklistServiceTest {
    private val standardRepository = mockk<StandardRepository>(relaxed = true)
    private val checklistRepository = mockk<ChecklistRepository>(relaxed = true)
    private val versionRepository = mockk<ChecklistVersionRepository>(relaxed = true)
    private val itemRepository = mockk<ChecklistItemRepository>(relaxed = true)
    private val bindingRepository = mockk<BindingRepository>(relaxed = true)
    private val auditService = mockk<AuditService>(relaxed = true)
    private val service = ChecklistService(
        standardRepository, checklistRepository, versionRepository, itemRepository, bindingRepository, auditService,
    )
    private val query = ChecklistQueryService(itemRepository, bindingRepository, versionRepository)

    @Test
    fun `createChecklist creates checklist plus first draft version V1`() {
        // Ruling #32: relaxed MockK defaults Boolean to false → unstubbed existsById(1L) would
        // make createChecklist throw BusinessException(404) (standard not found) and fail this test.
        // Task 2.2's green test stubbed existsByCode explicitly for the same reason (Ruling #13/#26 pattern).
        every { standardRepository.existsById(1L) } returns true
        every { checklistRepository.save(any()) } answers {
            firstArg<ComplianceChecklist>().apply { id = 10L }
        }
        every { versionRepository.save(any()) } answers {
            firstArg<ChecklistVersion>().apply { id = 20L }
        }
        val checklist = service.createChecklist(1L, "SEC-BASIC", "安全基线")
        assertEquals("SEC-BASIC", checklist.code)
        verify { versionRepository.save(match { it.status == VersionStatus.DRAFT && it.versionNo == "V1" }) }
    }

    @Test
    fun `publish snapshots items and opens no draft until next add`() {
        every { checklistRepository.findById(10L) } returns Optional.of(
            ComplianceChecklist().apply { id = 10L; code = "C"; name = "N" }
        )
        every { versionRepository.findFirstByChecklistIdAndStatusOrderByIdDesc(10L, VersionStatus.DRAFT) } returns
            ChecklistVersion().apply { id = 20L; checklistId = 10L; versionNo = "V1"; status = VersionStatus.DRAFT }
        every { versionRepository.findByChecklistIdOrderByVersionNoDesc(10L) } returns
            listOf(ChecklistVersion().apply { id = 20L; versionNo = "V1" })
        every { itemRepository.findByVersionId(20L) } returns
            listOf(ChecklistItem().apply { itemCode = "SEC-001"; name = "x" })
        every { versionRepository.save(any()) } answers { firstArg<ChecklistVersion>() }

        val published = service.publish(10L)
        assertEquals(VersionStatus.PUBLISHED, published.status)
        assertNotNull(published.contentSnapshot)
    }

    @Test
    fun `addItem after publish creates new draft version V2`() {
        every { checklistRepository.findById(10L) } returns Optional.of(
            ComplianceChecklist().apply { id = 10L; code = "C"; name = "N" }
        )
        every { versionRepository.findFirstByChecklistIdAndStatusOrderByIdDesc(10L, VersionStatus.DRAFT) } returns null
        every { versionRepository.findByChecklistIdOrderByVersionNoDesc(10L) } returns
            listOf(ChecklistVersion().apply { id = 20L; versionNo = "V2"; status = VersionStatus.PUBLISHED })
        every { versionRepository.save(any()) } answers {
            firstArg<ChecklistVersion>().apply { id = 30L; checklistId = 10L }
        }
        every { itemRepository.save(any()) } answers { firstArg<ChecklistItem>().apply { id = 99L } }

        val item = service.addItem(10L, AddItemCommand("SEC-002", "禁止硬编码密码", "HIGH"))
        assertEquals("SEC-002", item.itemCode)
        verify { versionRepository.save(match { it.versionNo == "V3" }) }
    }

    @Test
    fun `query returns published items for bound project`() {
        every { bindingRepository.findFirstByProjectIdOrderByIdDesc(1L) } returns
            ProjectChecklistBinding().apply { projectId = 1L; checklistVersionId = 20L }
        every { versionRepository.findById(20L) } returns Optional.of(
            ChecklistVersion().apply { id = 20L; status = VersionStatus.PUBLISHED }
        )
        every { itemRepository.findByVersionId(20L) } returns
            listOf(ChecklistItem().apply { itemCode = "SEC-001"; name = "x" })
        val items = query.publishedItemsForProject(1L)
        assertEquals(1, items!!.size)
    }
}
