package com.example.compliance.common.audit

import java.time.Instant

/** 审计日志查询过滤（全可选 AND，spec R-M16-D6）：空过滤 → 全量。 */
data class AuditLogFilter(
    val module: String? = null,
    val action: String? = null,
    val userId: Long? = null,
    val resourceType: String? = null,
    val resourceId: Long? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)
