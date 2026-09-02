package com.example.compliance.checklist.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "checklist_version")
class ChecklistVersion : BaseEntity() {
    @Column(name = "checklist_id", nullable = false)
    var checklistId: Long = 0
    @Column(name = "version_no", nullable = false, length = 32)
    lateinit var versionNo: String
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: VersionStatus = VersionStatus.DRAFT
    // Ruling #25: String on a jsonb column binds as varchar without @JdbcTypeCode (Ruling #13
    // pattern) — INSERT fails "column is of type jsonb but expression is of type character varying".
    // Task 3.2's versioning WILL write content_snapshot, so the annotation must be here now.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_snapshot", columnDefinition = "jsonb")
    var contentSnapshot: String? = null
    @Column(name = "published_at")
    var publishedAt: Instant? = null
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
