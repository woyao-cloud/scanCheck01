package com.example.compliance.admin.application

import com.example.compliance.common.api.PageView
import com.example.compliance.project.application.ProjectQueryPort
import com.example.compliance.result.application.FindingLifecyclePort
import com.example.compliance.result.application.FindingView
import com.example.compliance.result.domain.FindingStatus
import com.example.compliance.scan.application.ScanTaskQueryPort
import com.example.compliance.scan.application.ScanTaskView
import com.example.compliance.scan.domain.ScanTaskStatus
import org.springframework.stereotype.Service

/** admin 聚合查询（spec §6.2）。分页/计数按 RemediationService.list 的 MVP 内存切片模式（R-10.5-a）。 */
@Service
class AdminQueryService(
    private val projectQuery: ProjectQueryPort,
    private val scanTaskQuery: ScanTaskQueryPort,
    private val lifecycle: FindingLifecyclePort,
) {
    fun dashboard(): AdminDashboardView {
        val allFindings = lifecycle.findingsGlobal(null, null, null)
        return AdminDashboardView(
            projectCount = projectQuery.count(),
            scanTaskCount = scanTaskQuery.list(null, null, null).size.toLong(),
            findingCount = allFindings.size.toLong(),
            severityDistribution = allFindings.groupingBy { it.severity }.eachCount(),
        )
    }

    fun scans(projectId: Long?, engine: String?, status: ScanTaskStatus?, page: Int, size: Int): PageView<ScanTaskView> =
        pageOf(scanTaskQuery.list(projectId, engine, status), page, size)

    fun findings(projectId: Long?, status: FindingStatus?, severity: String?, page: Int, size: Int): PageView<FindingView> =
        pageOf(lifecycle.findingsGlobal(projectId, status, severity), page, size)

    private fun <T> pageOf(all: List<T>, page: Int, size: Int): PageView<T> {
        val from = page.coerceAtLeast(0) * size.coerceAtLeast(1)
        val items = if (from >= all.size) emptyList() else all.subList(from, minOf(from + size, all.size))
        return PageView(items, page, size, all.size.toLong())
    }
}
