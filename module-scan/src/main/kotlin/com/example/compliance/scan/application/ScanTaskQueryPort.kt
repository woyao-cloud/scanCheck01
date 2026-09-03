package com.example.compliance.scan.application

import com.example.compliance.scan.domain.ScanTaskStatus

/** admin 扫描任务查询端口（R-10.5-a：未分页过滤列表，分页/计数由 admin 侧切片）。 */
interface ScanTaskQueryPort {
    fun list(projectId: Long?, engine: String?, status: ScanTaskStatus?): List<ScanTaskView>
}
