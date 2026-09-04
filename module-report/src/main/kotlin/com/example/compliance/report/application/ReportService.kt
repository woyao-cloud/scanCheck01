package com.example.compliance.report.application

import com.example.compliance.common.exception.BusinessException
import com.example.compliance.result.application.FindingView
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.scan.infrastructure.ChecklistItemResultRepository
import com.example.compliance.scan.infrastructure.ComplianceEvaluationRepository
import com.example.compliance.scan.infrastructure.ScanTaskRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class ReportService(
    private val scanTaskRepository: ScanTaskRepository,
    private val findingRepository: FindingRepository,
    private val evaluationRepository: ComplianceEvaluationRepository,
    private val itemResultRepository: ChecklistItemResultRepository,
) {
    fun scanSummary(scanTaskId: Long): ScanSummary {
        val task = scanTaskRepository.findById(scanTaskId)
            .orElseThrow { BusinessException(404, "scan task not found: $scanTaskId") }
        // R-9.6-b: 统一指标口径——Finding → FindingView 私有映射后经 ReportMetrics 聚合；
        // publishedItemCount=0 → coveragePercent 恒为 0（ScanSummary DTO 不暴露该字段，无影响）。
        val views = findingRepository.findByProjectScanTask(scanTaskId).map { it.toView() }
        val metrics = ReportMetrics.from(views, score = null, publishedItemCount = 0)
        return ScanSummary(
            scanTaskId = task.id!!,
            engine = task.engine,
            status = task.status.name,
            findingCount = metrics.total,
            bySeverity = metrics.bySeverity,
        )
    }

    fun complianceSummary(projectId: Long): ComplianceSummary {
        val evaluation = evaluationRepository.findFirstByProjectIdOrderByIdDesc(projectId)
            ?: throw BusinessException(404, "no compliance evaluation for project: $projectId")
        val items = itemResultRepository.findByEvaluationId(evaluation.id!!)
        return ComplianceSummary(
            projectId = projectId,
            evaluationId = evaluation.id!!,
            score = evaluation.score,
            totalItems = evaluation.totalItems,
            passed = evaluation.passed,
            failed = evaluation.failed,
            warning = evaluation.warning,
            manual = evaluation.manual,
            skipped = evaluation.skipped,
            items = items.map { ItemSummary(it.itemCode, it.result, it.findingCount) },
            checklistVersionId = evaluation.checklistVersionId,   // M12: 快照可追溯引用（spec P3-D3）
        )
    }

    fun trend(projectId: Long, days: Int): List<TrendPoint> {
        val since = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        return evaluationRepository
            .findAllByProjectIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(projectId, since)
            .map { TrendPoint(it.createdAt.toString(), it.score, it.failed) }
    }

    /** Finding → FindingView 私有映射（与 FindingLifecycleService.toView 同形）。 */
    private fun com.example.compliance.result.domain.Finding.toView() = FindingView(
        id!!, projectId, scanTaskId, ruleCode, severity, status, filePath, lineNumber,
        firstSeenAt, lastSeenAt, occurrenceCount, engine,
    )
}
