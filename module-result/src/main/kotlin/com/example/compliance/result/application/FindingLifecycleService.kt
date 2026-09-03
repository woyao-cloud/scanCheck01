package com.example.compliance.result.application

import com.example.compliance.common.audit.AuditService
import com.example.compliance.common.event.FindingRegressionEvent
import com.example.compliance.common.exception.BusinessException
import com.example.compliance.result.domain.FindingEvidence
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.result.domain.FindingStatusSnapshot
import com.example.compliance.result.infrastructure.FindingEvidenceRepository
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.result.infrastructure.FindingStatusSnapshotRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** finding 生命周期唯一权威：所有状态转移、复扫验证、证据写入都经过这里（P2-D4）。
 *  F8 (final review m7)：移除注入但从未使用的 historyRepository（死依赖）。 */
@Service
class FindingLifecycleService(
    private val findingRepository: FindingRepository,
    private val statusRepository: FindingStatusSnapshotRepository,
    private val evidenceRepository: FindingEvidenceRepository,
    private val auditService: AuditService,
    private val eventPublisher: ApplicationEventPublisher,
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

    /** 复扫验证：扫描完成后调用。R-10.2-a（M10 I8）：只验证 requestId 解析出的目标 finding
     *  （recheck-f<id>），不再遍历项目全部 RECHECKING —— 并发多复扫不再误 CLOSED / 乐观锁冲突。
     *  单复扫行为与 spec §4.3 一致；targetFindingIds 空 → 不验证（非复扫扫描 no-op）。 */
    @Transactional
    override fun verifyRechecking(projectId: Long, scanTaskId: Long, presentFindingIds: Set<Long>, targetFindingIds: Set<Long>): VerifyResult {
        var closed = 0
        var regressed = 0
        val regressedIds = mutableListOf<Long>()
        findingRepository.findAll()
            .filter { it.projectId == projectId && it.status == FindingStatus.RECHECKING && it.id in targetFindingIds }
            .forEach { finding ->
                if (finding.id in presentFindingIds) {
                    transition(finding.id!!, FindingStatus.CONFIRMED, "regression_in_scan_$scanTaskId", null)
                    regressed++; regressedIds += finding.id!!
                } else {
                    transition(finding.id!!, FindingStatus.CLOSED, "verification_passed_in_scan_$scanTaskId", null)
                    closed++
                }
            }
        if (regressedIds.isNotEmpty()) {
            eventPublisher.publishEvent(FindingRegressionEvent(projectId, scanTaskId, regressedIds))
        }
        return VerifyResult(closed, regressed)
    }

    override fun findById(findingId: Long): FindingView? =
        findingRepository.findById(findingId).map { it.toView() }.orElse(null)

    override fun findingsGlobal(projectId: Long?, status: FindingStatus?, severity: String?): List<FindingView> =
        findingRepository.findAll()
            .asSequence()
            .filter { projectId == null || it.projectId == projectId }
            .filter { status == null || it.status == status }
            .filter { severity == null || it.severity.equals(severity, ignoreCase = true) }
            .map { it.toView() }
            .toList()

    private fun com.example.compliance.result.domain.Finding.toView() = FindingView(
        id!!, projectId, scanTaskId, ruleCode, severity, status, filePath, lineNumber,
        firstSeenAt, lastSeenAt, occurrenceCount, engine,
        packageName, packageVersion, fixedVersion, cveId, cvssScore?.toDouble(),
    )

    private fun quote(s: String?): String = "\"${s?.replace("\"", "\\\"") ?: ""}\""
}
