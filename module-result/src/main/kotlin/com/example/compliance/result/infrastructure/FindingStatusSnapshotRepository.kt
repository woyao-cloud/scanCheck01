package com.example.compliance.result.infrastructure

import com.example.compliance.result.domain.FindingStatusSnapshot
import org.springframework.data.jpa.repository.JpaRepository

interface FindingStatusSnapshotRepository : JpaRepository<FindingStatusSnapshot, Long> {
    fun findFirstByFindingIdOrderByChangedAtDesc(findingId: Long): FindingStatusSnapshot?
}
