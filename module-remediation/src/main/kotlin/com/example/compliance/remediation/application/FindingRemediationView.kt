package com.example.compliance.remediation.application

import com.example.compliance.result.application.FindingView

/** finding 中心响应：finding 全量视图 + 可空的整改任务元数据（未派单时为 null）。 */
data class FindingRemediationView(
    val finding: FindingView,
    val task: RemediationTaskView?,
)
