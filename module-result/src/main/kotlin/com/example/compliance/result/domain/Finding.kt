package com.example.compliance.result.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "finding")
class Finding : BaseEntity() {
    @Column(name = "scan_task_id", nullable = false)
    var scanTaskId: Long = 0
    @Column(name = "engine", nullable = false, length = 32)
    lateinit var engine: String
    @Column(name = "rule_code", nullable = false, length = 128)
    lateinit var ruleCode: String
    @Column(name = "rule_name", length = 256)
    var ruleName: String? = null
    @Column(name = "file_path", nullable = false)
    lateinit var filePath: String
    @Column(name = "line_number")
    var lineNumber: Int? = null
    @Column(name = "severity", nullable = false, length = 16)
    var severity: String = "LOW"
    @Column(name = "category", length = 64)
    var category: String? = null
    @Column(name = "message")
    var message: String? = null
    @Column(name = "code_snippet")
    var codeSnippet: String? = null
    @Column(name = "fingerprint", nullable = false, length = 64)
    lateinit var fingerprint: String
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: FindingStatus = FindingStatus.OPEN
    @Column(name = "first_seen_at", nullable = false)
    var firstSeenAt: Instant = Instant.now()
    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant = Instant.now()
    @Column(name = "occurrence_count", nullable = false)
    var occurrenceCount: Int = 1
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_json", columnDefinition = "jsonb")
    var rawJson: String? = null
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
