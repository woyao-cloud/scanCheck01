package com.example.compliance.admin.application

/** 管理后台仪表盘（spec §6.2）。 */
data class AdminDashboardView(
    val projectCount: Long,
    val scanTaskCount: Long,
    val findingCount: Long,
    val severityDistribution: Map<String, Int>,
)
