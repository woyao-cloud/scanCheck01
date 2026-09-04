package com.example.compliance.engineadapter.detekt

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanExecutionResult
import org.springframework.stereotype.Component
import java.io.File

/** Detekt 适配器（spec §5.2）：五方法镜像 SemgrepAdapter（代码类，无依赖字段）。 */
@Component
class DetektAdapter(
    private val cli: DetektCli,
    private val parser: DetektResultParser,
    private val severityMapper: DetektSeverityMapper,
) : ScanEngineAdapter {

    override val engine: String = "DETEKT"

    override fun prepareScan(context: ScanContext) {}

    override fun executeScan(context: ScanContext): ScanExecutionResult {
        val target = scanTarget(context)
        val start = System.currentTimeMillis()
        return runCatching {
            val stdout = cli.run(target)
            val file = stdoutFile(context)
            file.writeText(stdout)
            ScanExecutionResult(success = true, stdoutRef = file.absolutePath)
        }.getOrElse { e ->
            // F1（spec 2.1）: cli 失败（非 0 退出 / 超时）→ success=false，不落盘 stdout（绝不产出假干净扫描）
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
        File(System.getProperty("java.io.tmpdir"), "detekt-stdout-${context.scanTaskId}.sarif")

    private fun scanTarget(context: ScanContext): String = context.workDir ?: context.repoUrl
}
