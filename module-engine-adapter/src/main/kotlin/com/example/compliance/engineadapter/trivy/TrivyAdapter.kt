package com.example.compliance.engineadapter.trivy

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanExecutionResult
import org.springframework.stereotype.Component
import java.io.File

/** Trivy 依赖漏洞适配器（spec §6.4）：五方法镜像 SemgrepAdapter；依赖字段随 RawFinding 原样保留。 */
@Component
class TrivyAdapter(
    private val cli: TrivyCli,
    private val parser: TrivyResultParser,
    private val severityMapper: TrivySeverityMapper,
) : ScanEngineAdapter {

    override val engine: String = "TRIVY"

    override fun prepareScan(context: ScanContext) {
        // 无前置动作
    }

    override fun executeScan(context: ScanContext): ScanExecutionResult {
        val target = scanTarget(context)
        val start = System.currentTimeMillis()
        return runCatching {
            val stdout = cli.run(target)
            val file = stdoutFile(context)
            file.writeText(stdout)
            ScanExecutionResult(success = true, stdoutRef = file.absolutePath)
        }.getOrElse { e ->
            // cli 失败（非 0 退出 / 超时）→ success=false，不落盘 stdout（F1 同款）
            ScanExecutionResult(success = false, errorMessage = e.message, durationMs = System.currentTimeMillis() - start)
        }
    }

    override fun collectResult(context: ScanContext): List<RawFinding> {
        val file = stdoutFile(context)
        if (!file.exists()) return emptyList()
        return parser.parse(runCatching { file.readText() }.getOrDefault("{}"))
    }

    override fun normalizeResult(context: ScanContext, raw: List<RawFinding>): List<RawFinding> =
        raw.map { it.copy(severity = severityMapper.map(it.severity)) }

    override fun cleanup(context: ScanContext) {
        runCatching { stdoutFile(context).delete() }
    }

    private fun stdoutFile(context: ScanContext): File =
        File(System.getProperty("java.io.tmpdir"), "trivy-stdout-${context.scanTaskId}.json")

    private fun scanTarget(context: ScanContext): String = context.workDir ?: context.repoUrl
}
