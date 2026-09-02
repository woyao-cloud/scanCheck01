package com.example.compliance.rule.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*

@Entity
@Table(name = "rule_compliance_mapping")
class RuleComplianceMapping : BaseEntity() {
    @Column(name = "rule_id", nullable = false)
    var ruleId: Long = 0
    @Column(name = "checklist_item_code", nullable = false, length = 64)
    lateinit var checklistItemCode: String
}
