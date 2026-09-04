package com.example.compliance.engineadapter.semgrep

import com.example.compliance.engineadapter.cli.CliExecutor
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

interface SemgrepCli {
    fun run(targetPath: String, ref: String?): String
}

@Component
class ProcessSemgrepCli(
    @Value("\${app.semgrep.timeout-seconds:300}")
    private val timeoutSeconds: Long,
) : SemgrepCli {
    private val executor = CliExecutor(timeoutSeconds)
    override fun run(targetPath: String, ref: String?): String {
        val cmd = mutableListOf("semgrep", "--json", "--no-rewrite-rule-ids")
        ref?.let { cmd += listOf("--baseline-commit", it) }
        cmd += targetPath
        return executor.run(
            command = cmd,
            label = "semgrep",
            config = CliExecutor.Config(
                // F1（spec 2.1）: {0=clean, 1=命中} 成功；config/rule 错误可产出合法 JSON
                // {"errors":[...],"results":[]}，解析为干净扫描 → rescan 假 CLOSED，故 exit≥2 必须抛（含 stderr tail 诊断，spec 4.2）。
                mergeErrorStream = true,
                successExitCodes = setOf(0, 1),
            ),
        )
    }
}
