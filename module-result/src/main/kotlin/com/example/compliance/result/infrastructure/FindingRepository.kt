package com.example.compliance.result.infrastructure

import com.example.compliance.result.domain.Finding
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FindingRepository : JpaRepository<Finding, Long> {
    fun findByFingerprint(fingerprint: String): Finding?
    fun findByScanTaskId(scanTaskId: Long): List<Finding>

    /** 项目指纹规范行：复扫归属的基础。 */
    fun findByProjectIdAndFingerprint(projectId: Long, fingerprint: String): Finding?

    /** occurrence 查询：该扫描任务在 finding_history 中出现的全部 finding（含复现），按历史 id 排序。 */
    @Query(
        "SELECT f FROM Finding f JOIN FindingHistory h ON h.findingId = f.id " +
            "WHERE h.scanTaskId = :scanTaskId ORDER BY h.id"
    )
    fun findByProjectScanTask(@Param("scanTaskId") scanTaskId: Long): List<Finding>
}
