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
            // M13 P3-D8（M14 前置）: 依赖类判定收紧为 both-or-neither —— 单边（仅 packageName 或仅 cveId）是
            // 上游适配器 bug（契约保证 Trivy 恒两者同设、Gitleaks/代码类恒两者不设），显式失败优于 NPE。
            if ((f.packageName == null) != (f.cveId == null)) {
                throw IllegalArgumentException(
                    "dependency finding requires both packageName and cveId, got: packageName=${f.packageName}, cveId=${f.cveId}"
                )
            }
            val fingerprint = if (f.packageName != null && f.cveId != null)
                fingerprintGenerator.generateDependency(projectId, f.packageName, f.packageVersion, f.cveId)
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
                // M13 P3-D7: 依赖类 finding 复现时刷新整改指导元数据（advisory 更新后 fixedVersion/cvss 不再陈旧）。
                // 不碰 finding.status（P2-D4 状态权威不变），只刷 packageVersion/fixedVersion/cvssScore。
                if (existing.packageName != null && existing.cveId != null) {
                    existing.packageVersion = f.packageVersion
                    existing.fixedVersion = f.fixedVersion
                    existing.cvssScore = f.cvssScore?.toBigDecimal()
                }
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
