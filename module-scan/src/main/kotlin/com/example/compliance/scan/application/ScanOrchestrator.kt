package com.example.compliance.scan.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.project.application.ProjectService
import com.example.compliance.project.infrastructure.RepoRepository
import com.example.compliance.result.application.FindingService
import com.example.compliance.result.application.NewFinding
import com.example.compliance.result.engine.EngineAdapterRegistry
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.rule.application.RuleQueryService
import com.example.compliance.scan.checkout.GitCheckout
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
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
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
    private val checklistQueryService: com.example.compliance.checklist.application.ChecklistQueryService,
    private val lifecycleService: com.example.compliance.result.application.FindingLifecycleService,
    private val gitCheckout: GitCheckout,
    @Value("\${app.scan.checkout-engines:}") private val checkoutEngines: Set<String>,
) {
    private val objectMapper = ObjectMapper()

    /** 全流水线：PREPARING → 门控检出 → RUNNING → 五阶段扫描 → 归一化 → 指纹去重入库 → 合规判定 → 汇总评估（cleanup 在 finally）。 */
    // Ruling #52: 刻意不加 @Transactional（同 Ruling #45）—— 每个 repo save 自带事务立即提交，
    // catch 里的 FAILED 写入必然落库。若加外层 @Transactional，来自事务性协作方（如
    // FindingService.upsertByFingerprint）的异常会把共享事务标记 rollback-only，commit 时报
    // UnexpectedRollbackException，FAILED 写入被回滚 → 任务永远卡在 PENDING。
    @Async("scanExecutor")
    fun executeAsync(scanTaskId: Long) {
        val task = scanTaskRepository.findById(scanTaskId)
            .orElseThrow { BusinessException(404, "scan task not found: $scanTaskId") }
        task.status = ScanTaskStatus.PREPARING        // spec §5.3
        task.startedAt = Instant.now()
        scanTaskRepository.save(task)
        log(scanTaskId, "PREPARE", "INFO", "start engine=${task.engine} project=${task.projectId}")
        var adapter: ScanEngineAdapter? = null
        var context: ScanContext? = null
        var checkoutDir: String? = null
        val start = System.currentTimeMillis()
        try {
            val repo = repoRepository.findByProjectId(task.projectId).firstOrNull()
                ?: throw BusinessException(400, "project has no repository bound")
            val resolvedAdapter = registry.get(task.engine)
                ?: throw BusinessException(400, "unsupported engine: ${task.engine}")
            adapter = resolvedAdapter
            val version = checklistQueryService.publishedVersionForProject(task.projectId)
            task.checklistVersionId = version?.id
            log(scanTaskId, "PREPARE", "INFO", "checklistVersionId=${version?.id ?: "none"}")
            // 门控检出（spec §5.2）：仅 checkout-engines 中的引擎触发 clone；STUB 等跳过（commitId 保持 null）
            val checkout = if (task.engine.uppercase() in checkoutEngines) {
                gitCheckout.checkout(repo.gitUrl, task.ref).also { checkoutDir = it.workDir }
            } else null
            context = ScanContext(
                scanTaskId = task.id!!, projectId = task.projectId, repoUrl = repo.gitUrl,
                ref = task.ref, workDir = checkout?.workDir, commitId = checkout?.commitId,
            )
            task.status = ScanTaskStatus.RUNNING
            task.commitId = checkout?.commitId
            scanTaskRepository.save(task)

            // 五阶段管线（spec §5.1）：prepare → execute → collect → normalize
            resolvedAdapter.prepareScan(context!!)
            val execution = resolvedAdapter.executeScan(context!!)
            val raw = resolvedAdapter.collectResult(context!!)
            val normalizedRaw = resolvedAdapter.normalizeResult(context!!, raw)
            val duration = execution.durationMs ?: (System.currentTimeMillis() - start)
            if (!execution.success) {
                throw BusinessException(500, execution.errorMessage ?: "engine scan failed")
            }

            val normalized = ArrayList<NewFinding>()
            val ruleIds = mutableSetOf<Long>()
            var skipped = 0
            for (rawFinding in normalizedRaw) {
                val rule = ruleQueryService.publishedRuleByEngineRuleId(task.engine, rawFinding.engineRuleId)
                if (rule == null) { skipped++; continue }
                ruleIds += rule.id!!
                normalized += NewFinding(
                    rule.ruleCode, rule.name, rawFinding.filePath, rawFinding.line,
                    rawFinding.severity, rawFinding.category, rawFinding.message, rawFinding.codeSnippet,
                )
            }
            log(scanTaskId, "NORMALIZE", "INFO", "raw=${normalizedRaw.size} mapped=${normalized.size} skipped=$skipped")

            val upsert = findingService.upsertByFingerprint(task.projectId, scanTaskId, task.engine, normalized)
            val findings = findingRepository.findByProjectScanTask(scanTaskId)
            // 复扫验证（M7 闭环）：RECHECKING finding 缺席→CLOSED，命中→回归 CONFIRMED
            val presentIds = findings.mapNotNull { it.id }.toSet()
            val verify = lifecycleService.verifyRechecking(task.projectId, scanTaskId, presentIds)
            log(scanTaskId, "VERIFY", "INFO", "rechecking closed=${verify.closed} regressed=${verify.regressed}")

            task.ruleIds = objectMapper.writeValueAsString(ruleIds)

            scanJobRepository.save(ScanJob().apply {
                this.scanTaskId = scanTaskId
                engine = task.engine
                jobStatus = "SUCCESS"
                startedAt = task.startedAt
                finishedAt = Instant.now()
                durationMs = duration
                findingCount = findings.size
            })

            val evaluations = complianceEvaluator.evaluate(task.projectId, task.checklistVersionId, findings)
            if (evaluations.isNotEmpty()) {
                val evaluation = evaluationRepository.save(ComplianceEvaluation().apply {
                    this.scanTaskId = scanTaskId
                    this.projectId = task.projectId
                    checklistVersionId = task.checklistVersionId
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
                        checklistVersionId = task.checklistVersionId
                        findingCount = ev.findingCount
                        matchedFindingIds = objectMapper.writeValueAsString(ev.matchedFindingIds)
                    })
                }
            }

            task.status = ScanTaskStatus.SUCCESS
            task.findingCount = findings.size
            task.durationMs = duration
            task.finishedAt = Instant.now()
            scanTaskRepository.save(task)
            log(scanTaskId, "SCAN", "INFO", "done findings=${findings.size} created=${upsert.created} updated=${upsert.updated} evaluated=${evaluations.size}")
        } catch (e: Exception) {
            task.status = ScanTaskStatus.FAILED
            task.errorMessage = e.message?.take(500)
            task.finishedAt = Instant.now()
            scanTaskRepository.save(task)
            // F5 (final review I9): 复扫任务失败 → 把 RECHECKING finding 补偿回退 FIXED，避免卡死
            // （verifyRechecking 只在任务成功路径执行；RECHECKING 无其他出口）。Ruling #52：transition
            // 自带事务自提交。runCatching 保证补偿失败绝不影响上方的 FAILED 落库。
            task.requestId?.takeIf { it.startsWith("recheck-f") }?.let { requestId ->
                requestId.removePrefix("recheck-f").toLongOrNull()?.let { findingId ->
                    runCatching {
                        lifecycleService.transition(
                            findingId,
                            com.example.compliance.result.domain.FindingStatus.FIXED,
                            "recheck_failed_scan_$scanTaskId",
                            null,
                        )
                    }
                }
            }
            log(scanTaskId, "SCAN", "ERROR", e.message ?: "unknown failure")
        } finally {
            // spec §5.1/§5.2：adapter cleanup + 删除检出临时目录（均幂等，绝不触碰用户路径）
            context?.let { adapter?.cleanup(it) }
            checkoutDir?.let { gitCheckout.cleanup(it) }
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
