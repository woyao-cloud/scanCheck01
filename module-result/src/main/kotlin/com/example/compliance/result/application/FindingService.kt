package com.example.compliance.result.application

import com.example.compliance.result.domain.Finding
import com.example.compliance.result.domain.FindingHistory
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.result.infrastructure.FindingHistoryRepository
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.result.infrastructure.FingerprintGenerator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class NewFinding(
    val ruleCode: String,
    val ruleName: String?,
    val filePath: String,
    val lineNumber: Int?,
    val severity: String,
    val category: String?,
    val message: String?,
    val codeSnippet: String?,
    // M11 依赖类字段（Trivy 使用；代码类恒 null）。末尾默认值 → 既有 8 参位置调用点零破坏。
    val packageName: String? = null,
    val packageVersion: String? = null,
    val fixedVersion: String? = null,
    val cveId: String? = null,
    val cvssScore: Double? = null,
)

data class UpsertResult(val created: Int, val updated: Int)

/** 活动集：状态机当前所处位置，复现时保持不动。 */
private val ACTIVE_STATES = setOf(
    FindingStatus.NEW, FindingStatus.CONFIRMED, FindingStatus.ASSIGNED,
    FindingStatus.FIXING, FindingStatus.RECHECKING,
)

/** 豁免终态：基线 §7.3 已豁免/忽略的 finding 复现时跳过，保持终态。 */
private val WAIVED_STATES = setOf(
    FindingStatus.WAIVED, FindingStatus.IGNORED,
    FindingStatus.FALSE_POSITIVE, FindingStatus.ACCEPTED_RISK,
)

@Service
class FindingService(
    private val findingRepository: FindingRepository,
    private val historyRepository: FindingHistoryRepository,
    private val fingerprintGenerator: FingerprintGenerator,
    private val lifecycleService: FindingLifecycleService,
) {
    /** 按 (projectId, fingerprint) 规范行去重写入（P2-D2）：新指纹 CREATED，已有指纹 REAPPEARED + 状态机处置。 */
    @Transactional
    fun upsertByFingerprint(projectId: Long, scanTaskId: Long, engine: String, findings: List<NewFinding>): UpsertResult {
        var created = 0
        var updated = 0
        for (f in findings) {
            // M11：依赖类（packageName/cveId 非空）走依赖指纹（plan.md §7.3）；否则代码类既有指纹
            val fingerprint = if (f.packageName != null || f.cveId != null)
                fingerprintGenerator.generateDependency(projectId, f.packageName!!, f.packageVersion, f.cveId!!)
            else
                fingerprintGenerator.generate(projectId, f.ruleCode, f.filePath, f.lineNumber, f.codeSnippet)
            val existing = findingRepository.findByProjectIdAndFingerprint(projectId, fingerprint)
            if (existing == null) {
                findingRepository.save(Finding().apply {
                    this.projectId = projectId
                    this.scanTaskId = scanTaskId
                    this.engine = engine
                    ruleCode = f.ruleCode
                    ruleName = f.ruleName
                    filePath = f.filePath
                    lineNumber = f.lineNumber
                    severity = f.severity
                    category = f.category
                    message = f.message
                    codeSnippet = f.codeSnippet
                    packageName = f.packageName        // M11 依赖字段
                    packageVersion = f.packageVersion
                    fixedVersion = f.fixedVersion
                    cveId = f.cveId
                    cvssScore = f.cvssScore?.toBigDecimal()   // Double → BigDecimal（实体持 NUMERIC 列）
                    this.fingerprint = fingerprint
                }).let { saved ->
                    historyRepository.save(FindingHistory().apply {
                        findingId = saved.id!!; this.scanTaskId = scanTaskId; action = "CREATED"
                    })
                }
                created++
            } else {
                existing.occurrenceCount += 1
                existing.lastSeenAt = Instant.now()
                findingRepository.save(existing)
                historyRepository.save(FindingHistory().apply {
                    findingId = existing.id!!; this.scanTaskId = scanTaskId; action = "REAPPEARED"
                })
                when {
                    existing.status in ACTIVE_STATES -> Unit                       // 保持状态机当前位置
                    existing.status in WAIVED_STATES -> Unit                       // 已豁免，跳过
                    else -> lifecycleService.transition(existing.id!!, FindingStatus.CONFIRMED, "reappeared_after_fix", null) // FIXED/CLOSED → 回归
                }
                updated++
            }
        }
        return UpsertResult(created, updated)
    }
}
