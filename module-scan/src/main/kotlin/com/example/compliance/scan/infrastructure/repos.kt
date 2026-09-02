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
}

interface ChecklistItemResultRepository : JpaRepository<ChecklistItemResult, Long> {
    fun findByEvaluationId(evaluationId: Long): List<ChecklistItemResult>
}
