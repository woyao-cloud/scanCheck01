package com.example.compliance.result.application

import com.example.compliance.result.domain.Finding
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.result.domain.FindingTrace
import com.example.compliance.result.infrastructure.FindingRepository
import com.example.compliance.result.infrastructure.FindingTraceRepository
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
)

data class UpsertResult(val created: Int, val updated: Int)

@Service
class FindingService(
    private val findingRepository: FindingRepository,
    private val traceRepository: FindingTraceRepository,
    private val fingerprintGenerator: FingerprintGenerator,
) {
    /** 按指纹去重写入：新指纹插入（CREATED），已有指纹累加出现次数并回到 OPEN（UPDATED）。 */
    @Transactional
    fun upsertByFingerprint(projectId: Long, scanTaskId: Long, engine: String, findings: List<NewFinding>): UpsertResult {
        var created = 0
        var updated = 0
        for (f in findings) {
            val fingerprint = fingerprintGenerator.generate(projectId, f.ruleCode, f.filePath, f.lineNumber, f.codeSnippet)
            val existing = findingRepository.findByFingerprint(fingerprint)
            if (existing == null) {
                val saved = findingRepository.save(
                    Finding().apply {
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
                        this.fingerprint = fingerprint
                    }
                )
                traceRepository.save(FindingTrace().apply {
                    findingId = saved.id!!; this.scanTaskId = scanTaskId; action = "CREATED"
                })
                created++
            } else {
                existing.occurrenceCount += 1
                existing.lastSeenAt = Instant.now()
                existing.status = FindingStatus.NEW
                findingRepository.save(existing)
                traceRepository.save(FindingTrace().apply {
                    findingId = existing.id!!; this.scanTaskId = scanTaskId; action = "UPDATED"
                })
                updated++
            }
        }
        return UpsertResult(created, updated)
    }
}
