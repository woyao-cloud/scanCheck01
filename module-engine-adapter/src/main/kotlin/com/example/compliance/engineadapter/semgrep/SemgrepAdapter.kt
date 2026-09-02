package com.example.compliance.engineadapter.semgrep

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanResult
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class SemgrepAdapter(
    private val cli: SemgrepCli,
    private val parser: SemgrepResultParser,
    private val severityMapper: SemgrepSeverityMapper,
) : ScanEngineAdapter {

    override val engine: String = "SEMGREP"

    override fun scan(context: ScanContext): ScanResult {
        val target = localPathOf(context) ?: context.repoUrl
        val stdout = cli.run(target, context.ref)
        val raw = parser.parse(stdout)
        val normalized: List<RawFinding> = raw.map { it.copy(severity = severityMapper.map(it.severity)) }
        return ScanResult(findings = normalized)
    }

    /** P0：允许通过 configJson 提供本地检出目录 localPath，便于本地与测试运行。 */
    private fun localPathOf(context: ScanContext): String? =
        context.configJson?.let { json ->
            runCatching { ObjectMapper().readTree(json).path("localPath").takeIf { !it.isMissingNode }?.asText() }
                .getOrNull()
        }
}
