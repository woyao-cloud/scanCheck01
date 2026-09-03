package com.example.compliance.engineadapter.semgrep

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanExecutionResult
import org.springframework.stereotype.Component
import java.io.File

@Component
class SemgrepAdapter(
    private val cli: SemgrepCli,
    private val parser: SemgrepResultParser,
    private val severityMapper: SemgrepSeverityMapper,
) : ScanEngineAdapter {

    override val engine: String = "SEMGREP"

    override fun prepareScan(context: ScanContext) {
        // 无前置动作：超时与临时文件重定向由 SemgrepCli 负责（spec §5.2）
    }

    /** 执行 semgrep，stdout 落盘为按扫描任务隔离的临时文件并返回 stdoutRef（spec §5.1）。
     *  无实例可变状态：文件路径由 scanTaskId 派生（stdoutFile），并发扫描天然隔离（final review I7）。 */
    override fun executeScan(context: ScanContext): ScanExecutionResult {
        val target = scanTarget(context)
        val start = System.currentTimeMillis()
        return runCatching {
            val stdout = cli.run(target, context.ref)
            val file = stdoutFile(context)
            file.writeText(stdout)
            ScanExecutionResult(success = true, stdoutRef = file.absolutePath)
        }.getOrElse { e ->
            // F1 (final review C1): cli 失败（exit>=2 / 超时 / 非 JSON 输出）→ success=false，
            // 不落盘 stdout 文件（stdoutRef 缺席）；编排器据此把任务置 FAILED，绝不产出 0 finding 的假干净扫描。
            ScanExecutionResult(success = false, errorMessage = e.message, durationMs = System.currentTimeMillis() - start)
        }
    }

    /** 读取 stdout 文件并解析为引擎原生 finding（保留原始 severity，映射在 normalizeResult）。 */
    override fun collectResult(context: ScanContext): List<RawFinding> {
        val file = stdoutFile(context)
        if (!file.exists()) return emptyList()
        val content = runCatching { file.readText() }.getOrDefault("")
        return parser.parse(content)
    }

    /** severity 映射（Semgrep ERROR/WARNING/INFO → HIGH/MEDIUM/LOW）；ruleId→平台规则映射留在编排器。 */
    override fun normalizeResult(context: ScanContext, raw: List<RawFinding>): List<RawFinding> =
        raw.map { it.copy(severity = severityMapper.map(it.severity)) }

    /** 删除 executeScan 产生的 stdout 临时文件（不泄漏；文件不存在时 no-op）。 */
    override fun cleanup(context: ScanContext) {
        runCatching { stdoutFile(context).delete() }
    }

    /** stdout 临时文件路径：由扫描任务身份派生，天然并发隔离（final review I7）。 */
    private fun stdoutFile(context: ScanContext): File =
        File(System.getProperty("java.io.tmpdir"), "semgrep-stdout-${context.scanTaskId}.json")

    /** 扫描目标：优先编排器检出的 workDir，缺失回退 repoUrl（spec §5.2）。 */
    private fun scanTarget(context: ScanContext): String = context.workDir ?: context.repoUrl
}
