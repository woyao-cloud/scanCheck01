package com.example.compliance.report.infrastructure

import com.example.compliance.report.domain.ReportSnapshot
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ReportSnapshotRepository : JpaRepository<ReportSnapshot, Long> {
    fun findByProjectIdOrderByIdDesc(projectId: Long): List<ReportSnapshot>
    fun findBySnapshotTypeOrderByIdDesc(snapshotType: String): List<ReportSnapshot>
    fun findByProjectId(projectId: Long, pageable: Pageable): Page<ReportSnapshot>
    fun findBySnapshotType(snapshotType: String, pageable: Pageable): Page<ReportSnapshot>
    fun findByProjectIdAndSnapshotType(projectId: Long, snapshotType: String, pageable: Pageable): Page<ReportSnapshot>
}
