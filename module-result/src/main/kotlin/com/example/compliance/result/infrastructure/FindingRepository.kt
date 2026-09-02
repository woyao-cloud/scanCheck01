package com.example.compliance.result.infrastructure

import com.example.compliance.result.domain.Finding
import org.springframework.data.jpa.repository.JpaRepository

interface FindingRepository : JpaRepository<Finding, Long> {
    fun findByFingerprint(fingerprint: String): Finding?
    fun findByScanTaskId(scanTaskId: Long): List<Finding>
}
