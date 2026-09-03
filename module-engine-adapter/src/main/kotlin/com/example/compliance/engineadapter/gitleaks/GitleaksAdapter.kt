package com.example.compliance.engineadapter.gitleaks

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanExecutionResult
import org.springframework.stereotype.Component
import java.io.File

/** Gitleaks 密钥扫描适配器（spec §5.4）：五方法镜像 SemgrepAdapter —— 无实例可变状态，stdout 文件按 scanTaskId 派生。 */
@Component
class GitleaksAdapter(
    private val cli: GitleaksCli,
    private val parser: GitleaksResultParser,
    private val severityMapper: GitleaksSeverityMapper,
) : ScanEngineAdapter {

    override val engine: String = "GITLEAKS"

    override fun prepareScan(context: ScanContext) {
        // 无前置动作：超时与临时文件重定向由 GitleaksCli 负责
    }

    override fun executeScan(context: ScanContext): ScanExecutionResult {
        val target = scanTarget(context)
        val start = System.currentTimeMillis()
        return runCatching {
            val report = cli.run(target)
            val file = stdoutFile(context)
            file.writeText(report)
            ScanExecutionResult(success = true, stdoutRef = file.absolutePath)
        }.getOrElse { e ->
            // cli 失败（exit 非 0/1 / 超时）→ success=false，不落盘 stdout（F1 同款：绝不产出假干净扫描）
            ScanExecutionResult(success = false, errorMessage = e.message, durationMs = System.currentTimeMillis() - start)
        }
    }

    override fun collectResult(context: ScanContext): List<RawFinding> {
        val file = stdoutFile(context)
        if (!file.exists()) return emptyList()
        return parser.parse(runCatching { file.readText() }.getOrDefault("[]"))
    }

    override fun normalizeResult(context: ScanContext, raw: List<RawFinding>): List<RawFinding> =
        raw.map { it.copy(severity = severityMapper.map(it.severity)) }

    override fun cleanup(context: ScanContext) {
        runCatching { stdoutFile(context).delete() }
    }

    private fun stdoutFile(context: ScanContext): File =
        File(System.getProperty("java.io.tmpdir"), "gitleaks-report-${context.scanTaskId}.json")

    private fun scanTarget(context: ScanContext): String = context.workDir ?: context.repoUrl
}
