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

    /** executeScan 落盘路径：编排器对同一 adapter 顺序调用 executeScan→collectResult→cleanup，实例字段安全（spec §5.1 stdoutRef 语义）。 */
    @Volatile
    private var stdoutRef: String? = null

    override fun prepareScan(context: ScanContext) {
        // 无前置动作：超时与临时文件重定向由 SemgrepCli 负责（spec §5.2）
    }

    /** 执行 semgrep，stdout 落盘为临时文件并返回 stdoutRef。 */
    override fun executeScan(context: ScanContext): ScanExecutionResult {
        val target = scanTarget(context)
        val stdout = cli.run(target, context.ref)
        val file = File.createTempFile("semgrep-stdout-", ".json")
        file.writeText(stdout)
        stdoutRef = file.absolutePath
        return ScanExecutionResult(success = true, stdoutRef = file.absolutePath)
    }

    /** 读取 stdout 文件并解析为引擎原生 finding（保留原始 severity，映射在 normalizeResult）。 */
    override fun collectResult(context: ScanContext): List<RawFinding> {
        val ref = stdoutRef ?: return emptyList()
        val content = runCatching { File(ref).readText() }.getOrDefault("")
        return parser.parse(content)
    }

    /** severity 映射（Semgrep ERROR/WARNING/INFO → HIGH/MEDIUM/LOW）；ruleId→平台规则映射留在编排器。 */
    override fun normalizeResult(context: ScanContext, raw: List<RawFinding>): List<RawFinding> =
        raw.map { it.copy(severity = severityMapper.map(it.severity)) }

    /** 删除 executeScan 产生的 stdout 临时文件（不泄漏）。 */
    override fun cleanup(context: ScanContext) {
        stdoutRef?.let { runCatching { File(it).delete() } }
        stdoutRef = null
    }

    /** 扫描目标：优先编排器检出的 workDir，缺失回退 repoUrl（spec §5.2）。 */
    private fun scanTarget(context: ScanContext): String = context.workDir ?: context.repoUrl
}
