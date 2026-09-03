package com.example.compliance.scan.application

import com.example.compliance.project.application.ProjectService
import com.example.compliance.result.engine.EngineAdapterRegistry
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.scan.domain.ScanTask
import com.example.compliance.scan.domain.ScanTaskStatus
import com.example.compliance.scan.infrastructure.ChecklistItemResultRepository
import com.example.compliance.scan.infrastructure.ComplianceEvaluationRepository
import com.example.compliance.scan.infrastructure.ScanTaskRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** M7：ScanTaskService 实现 ScanTriggerPort 契约（值类型，不泄露实体）。 */
class ScanTriggerPortTest {

    private val scanTaskRepository = mockk<ScanTaskRepository>()
    private val projectService = mockk<ProjectService>()
    private val registry = mockk<EngineAdapterRegistry>()
    private val findingRepository = mockk<FindingRepository>()
    private val evaluationRepository = mockk<ComplianceEvaluationRepository>()
    private val itemResultRepository = mockk<ChecklistItemResultRepository>()
    private val orchestrator = mockk<ScanOrchestrator>(relaxed = true)
    private val service = ScanTaskService(
        scanTaskRepository, projectService, registry, findingRepository,
        evaluationRepository, itemResultRepository, orchestrator,
    )

    @Test
    fun `triggerScan passes requestId through and returns value view`() {
        val project = com.example.compliance.project.domain.Project().apply { id = 9L }
        every { registry.get("STUBM7") } returns mockk()
        every { projectService.get(9L) } returns project
        every { scanTaskRepository.save(any<ScanTask>()) } answers {
            firstArg<ScanTask>().apply { id = 42L }
        }

        val view = service.triggerScan(9L, "STUBM7", "main", "MANUAL", "recheck-f7")

        assertEquals(42L, view.id)
        assertEquals(9L, view.projectId)
        assertEquals("recheck-f7", view.requestId)
        assertEquals(ScanTaskStatus.PENDING, view.status)
        verify { scanTaskRepository.save(match { it.requestId == "recheck-f7" && it.triggerType == "MANUAL" }) }
    }
}
