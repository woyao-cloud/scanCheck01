package com.example.compliance.report.application

import com.example.compliance.common.exception.BusinessException
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
        val findings = findingRepository.findByProjectScanTask(scanTaskId)
        return ScanSummary(
            scanTaskId = task.id!!,
            engine = task.engine,
            status = task.status.name,
            findingCount = findings.size,
            bySeverity = findings.groupingBy { it.severity }.eachCount(),
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
        )
    }

    fun trend(projectId: Long, days: Int): List<TrendPoint> {
        val since = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        return evaluationRepository
            .findAllByProjectIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(projectId, since)
            .map { TrendPoint(it.createdAt.toString(), it.score, it.failed) }
    }
}
