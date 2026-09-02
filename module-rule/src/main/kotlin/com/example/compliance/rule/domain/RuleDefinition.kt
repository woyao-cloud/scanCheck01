package com.example.compliance.rule.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*

@Entity
@Table(name = "rule_definition")
class RuleDefinition : BaseEntity() {
    @Column(name = "rule_code", nullable = false, unique = true, length = 64)
    lateinit var ruleCode: String
    @Column(name = "name", nullable = false, length = 128)
    lateinit var name: String
    @Column(name = "risk_level", nullable = false, length = 16)
    var riskLevel: String = "MEDIUM"
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: RuleStatus = RuleStatus.DRAFT
    @Column(name = "description")
    var description: String? = null
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
