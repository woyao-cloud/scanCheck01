package com.example.compliance.scan.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "checklist_item_result")
class ChecklistItemResult : BaseEntity() {
    @Column(name = "evaluation_id", nullable = false)
    var evaluationId: Long = 0
    @Column(name = "item_code", nullable = false, length = 64)
    lateinit var itemCode: String
    @Column(name = "result", nullable = false, length = 16)
    lateinit var result: String
    @Column(name = "finding_count", nullable = false)
    var findingCount: Int = 0
    // Ruling #44: String 存 jsonb 列必须 @JdbcTypeCode(SqlTypes.JSON)（orchestrator 会写入 matchedFindingIds）
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "matched_finding_ids", columnDefinition = "jsonb")
    var matchedFindingIds: String? = null
}
