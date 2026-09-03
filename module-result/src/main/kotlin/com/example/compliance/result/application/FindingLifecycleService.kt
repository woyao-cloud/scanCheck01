package com.example.compliance.result.application

import com.example.compliance.common.audit.AuditService
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.result.domain.FindingEvidence
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.result.domain.FindingStatusSnapshot
import com.example.compliance.result.infrastructure.FindingEvidenceRepository
import com.example.compliance.result.infrastructure.FindingHistoryRepository
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.result.infrastructure.FindingStatusSnapshotRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** finding 生命周期唯一权威：所有状态转移、复扫验证、证据写入都经过这里（P2-D4）。 */
@Service
class FindingLifecycleService(
    private val findingRepository: FindingRepository,
    private val historyRepository: FindingHistoryRepository,
    private val statusRepository: FindingStatusSnapshotRepository,
    private val evidenceRepository: FindingEvidenceRepository,
    private val auditService: AuditService,
) : FindingLifecyclePort {

    override fun findingsForScanTask(scanTaskId: Long): List<FindingView> =
        findingRepository.findByProjectScanTask(scanTaskId).map { it.toView() }

    override fun findingsByProject(projectId: Long, status: FindingStatus?): List<FindingView> {
        val all = findingRepository.findAll().filter { it.projectId == projectId }
        return (status?.let { s -> all.filter { it.status == s } } ?: all).map { it.toView() }
    }

    @Transactional
    override fun transition(findingId: Long, to: FindingStatus, reason: String?, changedBy: Long?): FindingStatus {
        val finding = findingRepository.findById(findingId)
            .orElseThrow { BusinessException(404, "finding not found: $findingId") }
        val from = finding.status
        if (from == to) return to
        finding.status = to
        findingRepository.save(finding)
        statusRepository.save(FindingStatusSnapshot().apply {
            this.findingId = findingId; this.status = to; this.changedBy = changedBy; this.reason = reason
        })
        auditService.record("FINDING_TRANSITION", "result", changedBy, "finding", findingId, "{\"from\":\"$from\",\"to\":\"$to\",\"reason\":${quote(reason)}}")
        return to
    }

    @Transactional
    override fun addEvidence(findingId: Long, evidenceType: String, evidenceRef: String, changedBy: Long?): EvidenceView {
        val saved = evidenceRepository.save(FindingEvidence().apply {
            this.findingId = findingId; this.evidenceType = evidenceType; this.evidenceRef = evidenceRef; this.addedBy = changedBy
        })
        auditService.record("FINDING_EVIDENCE", "result", changedBy, "finding", findingId, "{\"type\":\"$evidenceType\",\"ref\":${quote(evidenceRef)}}")
        return EvidenceView(saved.id!!, saved.findingId, saved.evidenceType, saved.evidenceRef, saved.addedBy, saved.addedAt)
    }

    /** 复扫验证：扫描完成后调用。处于 RECHECKING 的 finding —— 本次扫描缺席 → CLOSED；命中 → 回归 CONFIRMED。 */
    @Transactional
    override fun verifyRechecking(projectId: Long, scanTaskId: Long, presentFindingIds: Set<Long>): VerifyResult {
        var closed = 0
        var regressed = 0
        findingRepository.findAll().filter { it.projectId == projectId && it.status == FindingStatus.RECHECKING }
            .forEach { finding ->
                if (finding.id in presentFindingIds) {
                    transition(finding.id!!, FindingStatus.CONFIRMED, "regression_in_scan_$scanTaskId", null)
                    regressed++
                } else {
                    transition(finding.id!!, FindingStatus.CLOSED, "verification_passed_in_scan_$scanTaskId", null)
                    closed++
                }
            }
        return VerifyResult(closed, regressed)
    }

    private fun com.example.compliance.result.domain.Finding.toView() = FindingView(
        id!!, projectId, scanTaskId, ruleCode, severity, status, filePath, lineNumber,
        firstSeenAt, lastSeenAt, occurrenceCount, engine,
    )

    private fun quote(s: String?): String = "\"${s?.replace("\"", "\\\"") ?: ""}\""
}
