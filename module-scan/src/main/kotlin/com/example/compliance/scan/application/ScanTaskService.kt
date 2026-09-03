package com.example.compliance.scan.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.project.application.ProjectService
import com.example.compliance.result.engine.EngineAdapterRegistry
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.scan.domain.ChecklistItemResult
import com.example.compliance.scan.domain.ComplianceEvaluation
import com.example.compliance.scan.domain.ScanTask
import com.example.compliance.scan.domain.ScanTaskStatus
import com.example.compliance.scan.infrastructure.ChecklistItemResultRepository
import com.example.compliance.scan.infrastructure.ComplianceEvaluationRepository
import com.example.compliance.scan.infrastructure.ScanTaskRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ScanTaskService(
    private val scanTaskRepository: ScanTaskRepository,
    private val projectService: ProjectService,
    private val registry: EngineAdapterRegistry,
    private val findingRepository: FindingRepository,
    private val evaluationRepository: ComplianceEvaluationRepository,
    private val itemResultRepository: ChecklistItemResultRepository,
    private val orchestrator: ScanOrchestrator,
) : ScanTriggerPort {
    /** 创建 PENDING 扫描任务并异步启动，立即返回任务。
     *  Ruling #45: 刻意不加 @Transactional —— save 自带事务立即提交，异步线程的
     *  findById 才能看到 PENDING 行；若在未提交事务内 dispatch，@Async 线程可能
     *  先于提交执行 → 任务 404 卡死在 PENDING（executeAsync 的 orElseThrow 在 try 外）。 */
    fun startScan(projectId: Long, engine: String, ref: String?, triggerType: String = "MANUAL", requestId: String? = null): ScanTask {
        if (registry.get(engine) == null) {
            throw BusinessException(400, "unsupported engine: $engine")
        }
        projectService.get(projectId)
        val task = scanTaskRepository.save(ScanTask().apply {
            this.projectId = projectId
            this.engine = engine
            this.ref = ref
            this.triggerType = triggerType
            this.requestId = requestId ?: java.util.UUID.randomUUID().toString()
        })
        orchestrator.executeAsync(task.id!!)
        return task
    }

    fun get(id: Long): ScanTask =
        scanTaskRepository.findById(id).orElseThrow { BusinessException(404, "scan task not found: $id") }

    /** P0：仅在 PENDING（尚未被异步线程接管）时可取消；RUNNING 后由执行器独占，取消留 P1。 */
    @Transactional
    fun cancel(id: Long): ScanTask {
        val task = get(id)
        if (task.status != ScanTaskStatus.PENDING) {
            throw BusinessException(400, "only PENDING scan can be cancelled, current: ${task.status}")
        }
        task.status = ScanTaskStatus.CANCELLED
        return scanTaskRepository.save(task)
    }

    fun findings(scanTaskId: Long) = findingRepository.findByProjectScanTask(scanTaskId)

    /** 扫描的合规评估结果（评估 + 逐条结果）；无评估返回空视图。 */
    fun complianceResults(scanTaskId: Long): ComplianceResultView {
        val task = get(scanTaskId)
        val evaluation = evaluationRepository.findByScanTaskId(scanTaskId)
        val items = evaluation?.let { itemResultRepository.findByEvaluationId(it.id!!) } ?: emptyList()
        return ComplianceResultView(task, evaluation, items)
    }

    data class ComplianceResultView(
        val scanTask: ScanTask,
        val evaluation: ComplianceEvaluation?,
        val items: List<ChecklistItemResult>,
    )

    override fun triggerScan(projectId: Long, engine: String, ref: String?, triggerType: String, requestId: String?): ScanTaskView {
        val task = startScan(projectId, engine, ref, triggerType, requestId)
        return ScanTaskView(task.id!!, task.projectId, task.engine, task.status, task.requestId ?: "")
    }
}
