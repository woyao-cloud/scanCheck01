package com.example.compliance.scan.application

import com.example.compliance.scan.domain.ScanTaskStatus

/** 复扫任务视图（值类型，不泄露 ScanTask 实体）。 */
data class ScanTaskView(
    val id: Long,
    val projectId: Long,
    val engine: String,
    val status: ScanTaskStatus,
    val requestId: String,
)

/** 复扫触发端口（spec §4.3/§4.4）：remediation 经此创建复扫任务。P2-D5 例外——remediation→module-scan 仅依赖此接口。 */
interface ScanTriggerPort {
    fun triggerScan(projectId: Long, engine: String, ref: String?, triggerType: String, requestId: String?): ScanTaskView
}
