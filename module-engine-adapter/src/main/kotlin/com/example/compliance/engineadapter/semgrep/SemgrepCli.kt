package com.example.compliance.engineadapter.semgrep

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.TimeUnit

interface SemgrepCli {
    fun run(targetPath: String, ref: String?): String
}

@Component
class ProcessSemgrepCli(
    @Value("\${app.semgrep.timeout-seconds:300}")
    private val timeoutSeconds: Long,
) : SemgrepCli {
    override fun run(targetPath: String, ref: String?): String {
        val cmd = mutableListOf("semgrep", "--json", "--no-rewrite-rule-ids")
        ref?.let { cmd += listOf("--baseline-commit", it) }
        cmd += targetPath
        val tmp = File.createTempFile("semgrep-out-", ".json")
        try {
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(tmp)
                .start()
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
                throw IllegalStateException("semgrep timed out after ${timeoutSeconds}s")
            }
            // F1 (final review C1): semgrep 退出语义 —— 0=clean、1=命中 finding、>=2=错误。
            // 配置/规则错误仍可能输出合法 JSON {"errors":[...],"results":[]}，若不拦截会被解析成
            // 0 finding 的「干净扫描」，复扫路径误 CLOSED。exit>=2 必须抛异常（主检；parser 再兜底）。
            if (process.exitValue() >= 2) {
                throw IllegalStateException("semgrep exited with code ${process.exitValue()}")
            }
            return tmp.readText()
        } finally {
            tmp.delete()
        }
    }
}
