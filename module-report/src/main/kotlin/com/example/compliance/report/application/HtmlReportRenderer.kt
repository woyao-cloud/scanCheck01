package com.example.compliance.report.application

import com.example.compliance.report.domain.ReportSnapshot
import com.fasterxml.jackson.databind.ObjectMapper

/** HTML 导出渲染：固定模板（不引入模板库），payload 顶层键值表 + 元数据头。值经 HTML 转义。 */
object HtmlReportRenderer {
    private val objectMapper = ObjectMapper()

    fun render(snapshot: ReportSnapshot): String {
        val root = objectMapper.readTree(snapshot.payload)
        val rows = root.fields().asSequence().joinToString("") { (k, v) ->
            "<tr><td>${escape(k)}</td><td>${escape(v.toString())}</td></tr>"
        }
        return """<html><head><title>Report #${snapshot.id} (${snapshot.snapshotType})</title></head>
<body><h1>${escape(snapshot.snapshotType)} report #${snapshot.id}</h1>
<p>template v${snapshot.templateVersionNo} &middot; generatedAt ${snapshot.generatedAt}</p>
<table border="1"><tr><th>key</th><th>value</th></tr>$rows</table></body></html>"""
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
