package com.example.compliance.notification.application

/** 6 类型内置默认标题/正文（= V15 播种同文，Kotlin 常量）。渲染无 PUBLISHED 模板时回落（R-M18-7）。 */
object DefaultNotificationTemplates {
    val TITLES: Map<String, String> = mapOf(
        "SCAN_COMPLETED" to "scan completed",
        "REPORT_SNAPSHOT_GENERATED" to "report snapshot generated",
        "REMEDIATION_ASSIGNED" to "remediation assigned",
        "REMEDIATION_COMPLETED" to "remediation completed",
        "FINDING_REGRESSION" to "finding regressed",
        "REMEDIATION_WAIVER" to "finding waived",
    )
    val BODIES: Map<String, String> = mapOf(
        "SCAN_COMPLETED" to "扫描 {scanTaskId} 完成：{status}",
        "REPORT_SNAPSHOT_GENERATED" to "快照 {snapshotId} 已生成（{snapshotType}）",
        "REMEDIATION_ASSIGNED" to "finding {findingId} 已指派给你",
        "REMEDIATION_COMPLETED" to "finding {findingId} 已标记完成",
        "FINDING_REGRESSION" to "回归：{findingCount} 个 finding 在扫描 {scanTaskId} 复现",
        "REMEDIATION_WAIVER" to "finding {findingId} 被豁免（{reason}）",
    )
}
