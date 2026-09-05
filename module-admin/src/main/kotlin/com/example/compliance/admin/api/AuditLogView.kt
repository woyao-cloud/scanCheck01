package com.example.compliance.admin.api

import com.example.compliance.common.audit.AuditLog

/** 审计日志查询视图（spec §3.3）：detail 原样字符串（JSONB 原始文本，客户端自行解析）。 */
data class AuditLogView(
    val id: Long,
    val userId: Long?,
    val action: String,
    val module: String,
    val resourceType: String?,
    val resourceId: Long?,
    val detail: String?,
    val ip: String?,
    val occurredAt: String,
) {
    companion object {
        fun from(e: AuditLog) = AuditLogView(
            e.id!!, e.userId, e.action, e.module, e.resourceType, e.resourceId, e.detail, e.ip, e.occurredAt.toString(),
        )
    }
}
