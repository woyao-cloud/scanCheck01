package com.example.compliance.checklist.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "checklist_item")
class ChecklistItem : BaseEntity() {
    @Column(name = "version_id", nullable = false)
    var versionId: Long = 0
    @Column(name = "item_code", nullable = false, length = 64)
    lateinit var itemCode: String
    @Column(name = "name", nullable = false, length = 128)
    lateinit var name: String
    @Column(name = "category", length = 64)
    var category: String? = null
    @Column(name = "risk_level", nullable = false, length = 16)
    var riskLevel: String = "MEDIUM"
    @Column(name = "description")
    var description: String? = null
    @Column(name = "basis")
    var basis: String? = null
    @Column(name = "remediation")
    var remediation: String? = null
    @Column(name = "required", nullable = false)
    var required: Boolean = true
    @Column(name = "waivable", nullable = false)
    var waivable: Boolean = false
    @Column(name = "score_weight", nullable = false, precision = 6, scale = 3)
    var scoreWeight: BigDecimal = BigDecimal.ONE
    @Column(name = "effective_from")
    var effectiveFrom: Instant? = null
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
