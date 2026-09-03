package com.example.compliance.result.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant

/** finding_evidence：整改证据引用（FIX_COMMIT / SCREENSHOT / DOC / LINK）。 */
@Entity
@Table(name = "finding_evidence")
class FindingEvidence : BaseEntity() {
    @Column(name = "finding_id", nullable = false)
    var findingId: Long = 0
    @Column(name = "evidence_type", nullable = false, length = 32)
    lateinit var evidenceType: String
    @Column(name = "evidence_ref", nullable = false, length = 512)
    lateinit var evidenceRef: String
    @Column(name = "added_by")
    var addedBy: Long? = null
    @Column(name = "added_at", nullable = false)
    var addedAt: Instant = Instant.now()
}
