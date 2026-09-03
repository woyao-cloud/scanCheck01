package com.example.compliance.result.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*
import java.time.Instant

/** finding_status：状态转移快照（追加式）；finding.status 镜像最新一行。 */
@Entity
@Table(name = "finding_status")
class FindingStatusSnapshot : BaseEntity() {
    @Column(name = "finding_id", nullable = false)
    var findingId: Long = 0
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: FindingStatus = FindingStatus.NEW
    @Column(name = "changed_by")
    var changedBy: Long? = null
    @Column(name = "changed_at", nullable = false)
    var changedAt: Instant = Instant.now()
    @Column(name = "reason")
    var reason: String? = null
}
