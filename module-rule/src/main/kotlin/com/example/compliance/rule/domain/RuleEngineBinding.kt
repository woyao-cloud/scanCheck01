package com.example.compliance.rule.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "rule_engine_binding")
class RuleEngineBinding : BaseEntity() {
    @Column(name = "rule_id", nullable = false)
    var ruleId: Long = 0
    @Column(name = "engine", nullable = false, length = 32)
    lateinit var engine: String
    @Column(name = "engine_rule_id", nullable = false, length = 128)
    lateinit var engineRuleId: String
    // Ruling #33: String on a jsonb column binds as varchar without @JdbcTypeCode (Ruling #13/#25
    // pattern) — INSERT fails "column is of type jsonb but expression is of type character varying".
    // Not exercised by Task 3.3's tests (engineConfigJson/policyJson never written there), but M4's
    // rule-policy writes would hit it. Annotation required now.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "engine_config_json", columnDefinition = "jsonb")
    var engineConfigJson: String? = null
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
}
