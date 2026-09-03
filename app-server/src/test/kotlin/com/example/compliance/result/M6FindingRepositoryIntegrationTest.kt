package com.example.compliance.result

import com.example.compliance.AbstractIntegrationTest
import com.example.compliance.result.domain.Finding
import com.example.compliance.result.domain.FindingHistory
import com.example.compliance.result.infrastructure.FindingHistoryRepository
import com.example.compliance.result.infrastructure.FindingRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// M6-* 前缀数据，与共享容器中的 SEC-*/SEC2-*/PIPE-*/RPT-* 不冲突（Ruling #43 类约束）。
// 注：brief 中的块注释原文含 */（如 SEC-*/），会提前终止 /* */ 注释导致编译失败，此处改为行注释。
class M6FindingRepositoryIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var findingRepository: FindingRepository
    @Autowired lateinit var historyRepository: FindingHistoryRepository

    @Test
    fun `occurrence query returns findings seen in a scan task via history`() {
        val fp = "m6f-" + "b".repeat(60)
        val finding = findingRepository.save(Finding().apply {
            projectId = 99001L; scanTaskId = 99001L; engine = "STUB"; ruleCode = "M6F-001"
            filePath = "B.java"; lineNumber = 2; severity = "HIGH"; fingerprint = fp
        })
        historyRepository.save(FindingHistory().apply {
            findingId = finding.id!!; scanTaskId = 99001L; action = "CREATED"
        })
        // 另一个扫描任务（99002）复现同一指纹 → REAPPEARED
        historyRepository.save(FindingHistory().apply {
            findingId = finding.id!!; scanTaskId = 99002L; action = "REAPPEARED"
        })

        val seenInFirst = findingRepository.findByProjectScanTask(99001L)
        val seenInSecond = findingRepository.findByProjectScanTask(99002L)
        assertEquals(1, seenInFirst.size)
        assertEquals("M6F-001", seenInFirst[0].ruleCode)
        assertEquals(1, seenInSecond.size)
        assertEquals("M6F-001", seenInSecond[0].ruleCode)

        assertEquals(fp, findingRepository.findByProjectIdAndFingerprint(99001L, fp)?.fingerprint)
        assertNull(findingRepository.findByProjectIdAndFingerprint(99999L, fp))
    }
}
