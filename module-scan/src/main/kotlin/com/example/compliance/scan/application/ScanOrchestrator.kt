package com.example.compliance.scan.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.project.application.ProjectService
import com.example.compliance.project.infrastructure.RepoRepository
import com.example.compliance.result.application.FindingService
import com.example.compliance.result.application.NewFinding
import com.example.compliance.result.engine.EngineAdapterRegistry
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.rule.application.RuleQueryService
import com.example.compliance.scan.domain.ComplianceEvaluation
import com.example.compliance.scan.domain.ChecklistItemResult
import com.example.compliance.scan.domain.ScanExecutionLog
import com.example.compliance.scan.domain.ScanJob
import com.example.compliance.scan.domain.ScanTask
import com.example.compliance.scan.domain.ScanTaskStatus
import com.example.compliance.scan.infrastructure.ChecklistItemResultRepository
import com.example.compliance.scan.infrastructure.ComplianceEvaluationRepository
import com.example.compliance.scan.infrastructure.ScanExecutionLogRepository
import com.example.compliance.scan.infrastructure.ScanJobRepository
import com.example.compliance.scan.infrastructure.ScanTaskRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

@Component
class ScanOrchestrator(
    private val scanTaskRepository: ScanTaskRepository,
    private val scanJobRepository: ScanJobRepository,
    private val scanLogRepository: ScanExecutionLogRepository,
    private val evaluationRepository: ComplianceEvaluationRepository,
    private val itemResultRepository: ChecklistItemResultRepository,
    private val projectService: ProjectService,
    private val repoRepository: RepoRepository,
    private val registry: EngineAdapterRegistry,
    private val findingService: FindingService,
    private val findingRepository: FindingRepository,
    private val ruleQueryService: RuleQueryService,
    private val complianceEvaluator: ComplianceEvaluator,
) {
    private val objectMapper = ObjectMapper()

    /** 全流水线：RUNNING → adapter 扫描 → 归一化 → 指纹去重入库 → 合规判定 → 汇总评估。 */
    @Async("scanExecutor")
    @Transactional
    fun executeAsync(scanTaskId: Long) {
        val task = scanTaskRepository.findById(scanTaskId)
            .orElseThrow { BusinessException(404, "scan task not found: $scanTaskId") }
        task.status = ScanTaskStatus.RUNNING
        task.startedAt = Instant.now()
        scanTaskRepository.save(task)
        log(scanTaskId, "SCAN", "INFO", "start engine=${task.engine} project=${task.projectId}")
        try {
            val repo = repoRepository.findByProjectId(task.projectId).firstOrNull()
                ?: throw BusinessException(400, "project has no repository bound")
            val adapter = registry.get(task.engine)
                ?: throw BusinessException(400, "unsupported engine: ${task.engine}")
            val context = ScanContext(task.id!!, task.projectId, repo.gitUrl, task.ref)
            val start = System.currentTimeMillis()
            val result = adapter.scan(context)
            val duration = System.currentTimeMillis() - start

            if (!result.success) {
                throw BusinessException(500, result.errorMessage ?: "engine scan failed")
            }

            val normalized = ArrayList<NewFinding>()
            var skipped = 0
            for (raw in result.findings) {
                val rule = ruleQueryService.publishedRuleByEngineRuleId(task.engine, raw.engineRuleId)
                if (rule == null) { skipped++; continue }
                normalized += NewFinding(
                    rule.ruleCode, rule.name, raw.filePath, raw.line,
                    raw.severity, raw.category, raw.message, raw.codeSnippet,
                )
            }
            log(scanTaskId, "NORMALIZE", "INFO", "raw=${result.findings.size} mapped=${normalized.size} skipped=$skipped")

            val upsert = findingService.upsertByFingerprint(task.projectId, scanTaskId, task.engine, normalized)
            val findings = findingRepository.findByScanTaskId(scanTaskId)

            scanJobRepository.save(ScanJob().apply {
                this.scanTaskId = scanTaskId
                engine = task.engine
                jobStatus = "SUCCESS"
                startedAt = task.startedAt
                finishedAt = Instant.now()
                durationMs = duration
                findingCount = findings.size
            })

            val evaluations = complianceEvaluator.evaluate(task.projectId, findings)
            if (evaluations.isNotEmpty()) {
                val evaluation = evaluationRepository.save(ComplianceEvaluation().apply {
                    this.scanTaskId = scanTaskId
                    this.projectId = task.projectId
                    totalItems = evaluations.size
                    passed = evaluations.count { it.result.name == "PASS" }
                    failed = evaluations.count { it.result.name == "FAIL" }
                    warning = evaluations.count { it.result.name == "WARNING" }
                    score = BigDecimal(100.0 * passed / evaluations.size)
                        .setScale(2, RoundingMode.HALF_UP)
                })
                evaluations.forEach { ev ->
                    itemResultRepository.save(ChecklistItemResult().apply {
                        evaluationId = evaluation.id!!
                        itemCode = ev.itemCode
                        this.result = ev.result.name
                        findingCount = ev.findingCount
                        matchedFindingIds = objectMapper.writeValueAsString(ev.matchedFindingIds)
                    })
                }
            }

            task.status = ScanTaskStatus.SUCCESS
            task.findingCount = findings.size
            task.finishedAt = Instant.now()
            scanTaskRepository.save(task)
            log(scanTaskId, "SCAN", "INFO", "done findings=${findings.size} created=${upsert.created} updated=${upsert.updated} evaluated=${evaluations.size}")
        } catch (e: Exception) {
            task.status = ScanTaskStatus.FAILED
            task.errorMessage = e.message?.take(500)
            task.finishedAt = Instant.now()
            scanTaskRepository.save(task)
            log(scanTaskId, "SCAN", "ERROR", e.message ?: "unknown failure")
        }
    }

    private fun log(scanTaskId: Long, stage: String, level: String, message: String) {
        scanLogRepository.save(ScanExecutionLog().apply {
            this.scanTaskId = scanTaskId
            this.stage = stage
            this.level = level
            this.message = message
        })
    }
}
