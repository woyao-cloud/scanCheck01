package com.example.compliance.report.infrastructure

import com.example.compliance.report.domain.ReportSnapshot
import org.springframework.data.jpa.repository.JpaRepository

interface ReportSnapshotRepository : JpaRepository<ReportSnapshot, Long> {
    fun findByProjectIdOrderByIdDesc(projectId: Long): List<ReportSnapshot>
    fun findBySnapshotTypeOrderByIdDesc(snapshotType: String): List<ReportSnapshot>
}
