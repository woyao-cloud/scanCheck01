package com.example.compliance.scan.infrastructure

import com.example.compliance.scan.domain.ChecklistItemResult
import com.example.compliance.scan.domain.ComplianceEvaluation
import com.example.compliance.scan.domain.ScanExecutionLog
import com.example.compliance.scan.domain.ScanJob
import com.example.compliance.scan.domain.ScanTask
import org.springframework.data.jpa.repository.JpaRepository

interface ScanTaskRepository : JpaRepository<ScanTask, Long> {
    fun findByProjectIdOrderByIdDesc(projectId: Long): List<ScanTask>
}

interface ScanJobRepository : JpaRepository<ScanJob, Long>

interface ScanExecutionLogRepository : JpaRepository<ScanExecutionLog, Long> {
    fun findByScanTaskId(scanTaskId: Long): List<ScanExecutionLog>
}

interface ComplianceEvaluationRepository : JpaRepository<ComplianceEvaluation, Long> {
    fun findByScanTaskId(scanTaskId: Long): ComplianceEvaluation?
    fun findFirstByProjectIdOrderByIdDesc(projectId: Long): ComplianceEvaluation?

    /** M5 趋势分析使用：按时间升序返回项目指定时间之后的评估。 */
    fun findAllByProjectIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
        projectId: Long,
        since: java.time.Instant,
    ): List<ComplianceEvaluation>
}

interface ChecklistItemResultRepository : JpaRepository<ChecklistItemResult, Long> {
    fun findByEvaluationId(evaluationId: Long): List<ChecklistItemResult>
}
