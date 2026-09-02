package com.example.compliance.checklist.domain

import com.example.compliance.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "checklist_item_detail")
class ChecklistItemDetail : BaseEntity() {
    @Column(name = "item_id", nullable = false)
    var itemId: Long = 0
    // Ruling #25: same jsonb binding requirement as ChecklistVersion.contentSnapshot.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "jsonb")
    var detailJson: String? = null
}
