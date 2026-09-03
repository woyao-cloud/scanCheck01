package com.example.compliance.result.application

import com.example.compliance.result.domain.FindingStatus

/** 状态转移 DTO：module-remediation 只经此接口读写 finding 生命周期（P2-D5），禁止 import 实体。 */
data class FindingView(
    val id: Long,
    val projectId: Long,
    val scanTaskId: Long,
    val ruleCode: String,
    val severity: String,
    val status: FindingStatus,
    val filePath: String,
    val lineNumber: Int?,
    val firstSeenAt: java.time.Instant,
    val lastSeenAt: java.time.Instant,
    val occurrenceCount: Int,
    val engine: String = "",   // 发现引擎（M7 复扫定位、M8 引擎契约断言消费）
)

/** 证据 DTO（P2-D5：端口不泄漏实体）。 */
data class EvidenceView(
    val id: Long,
    val findingId: Long,
    val evidenceType: String,
    val evidenceRef: String,
    val addedBy: Long?,
    val addedAt: java.time.Instant,
)

data class VerifyResult(val closed: Int, val regressed: Int)

/** finding 生命周期权威端口。实现：module-result 的 FindingLifecycleService。 */
interface FindingLifecyclePort {
    fun transition(findingId: Long, to: FindingStatus, reason: String?, changedBy: Long?): FindingStatus
    fun addEvidence(findingId: Long, evidenceType: String, evidenceRef: String, changedBy: Long?): EvidenceView
    fun findingsForScanTask(scanTaskId: Long): List<FindingView>
    fun findingsByProject(projectId: Long, status: FindingStatus?): List<FindingView>
    fun verifyRechecking(projectId: Long, scanTaskId: Long, presentFindingIds: Set<Long>, targetFindingIds: Set<Long>): VerifyResult
    fun findById(findingId: Long): FindingView?
}
