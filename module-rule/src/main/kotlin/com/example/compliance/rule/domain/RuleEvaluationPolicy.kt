package com.example.compliance.rule.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "rule_evaluation_policy")
class RuleEvaluationPolicy : BaseEntity() {
    @Column(name = "rule_id", nullable = false, unique = true)
    var ruleId: Long = 0
    @Column(name = "result_on_match", nullable = false, length = 16)
    var resultOnMatch: String = "FAIL"
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_json", columnDefinition = "jsonb")
    var policyJson: String? = null
    @Column(name = "sp_el_expression")
    var spElExpression: String? = null
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
