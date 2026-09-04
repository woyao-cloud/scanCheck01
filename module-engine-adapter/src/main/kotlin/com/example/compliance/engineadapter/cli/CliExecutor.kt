package com.example.compliance.engineadapter.cli

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 共享 CLI 进程执行器（spec P3-D9）：参数化超时、stdout/stderr 重定向模式、成功退出码、JSON 来源、失败 tail 诊断。
 * 纯重构抽取 —— Semgrep/Gitleaks/Trivy 三 Process*Cli 对外语义不变（Semgrep 额外获得失败 tail 诊断，spec 4.2）。
 * 稳健设计（R-8.2-b 教训）：stdout/stderr 各自重定向独立临时文件（或合并单文件），绝无未读管道 → 不假超时。
 */
class CliExecutor(private val timeoutSeconds: Long) {

    /** 一次执行的可变参数。 */
    class Config(
        val mergeErrorStream: Boolean,          // semgrep=true（redirectErrorStream 合并单文件）；gitleaks/trivy=false（双文件）
        val successExitCodes: Set<Int>,         // semgrep={0,1}（0=clean、1=命中）；gitleaks={0,1}；trivy={0}
        val resultFile: File? = null,           // gitleaks=--report-path 文件（JSON 来源）；null → 读 stdout 文件
        val includeStdoutTail: Boolean = false, // trivy=true；gitleaks=false
    )

    fun run(command: List<String>, label: String, config: Config): String {
        val out = File.createTempFile("cli-out-", ".log")
        val err = if (config.mergeErrorStream) null else File.createTempFile("cli-err-", ".log")
        try {
            val pb = ProcessBuilder(command)
            pb.redirectOutput(out)
            if (config.mergeErrorStream) pb.redirectErrorStream(true) else pb.redirectError(err!!)
            val process = pb.start()
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
                throw IllegalStateException("$label timed out after ${timeoutSeconds}s; ${diagTail(config, err, out)}")
            }
            val code = process.exitValue()
            if (code !in config.successExitCodes) {
                throw IllegalStateException("$label exited with code $code; ${diagTail(config, err, out)}")
            }
            return if (config.resultFile != null) {
                if (config.resultFile.exists()) config.resultFile.readText() else "[]"
            } else {
                out.readText()
            }
        } finally {
            out.delete(); err?.delete(); config.resultFile?.delete()
        }
    }

    /** 失败诊断：merged 模式 stderr 已并流 → 读合并文件尾部；split 模式读 err（+ 可选 stdout）尾部。 */
    private fun diagTail(config: Config, err: File?, out: File): String =
        if (config.mergeErrorStream) "stderr: ${tailOf(out)}"
        else {
            val parts = mutableListOf("stderr: ${tailOf(err!!)}")
            if (config.includeStdoutTail) parts += "stdout: ${tailOf(out)}"
            parts.joinToString("; ")
        }

    private fun tailOf(file: File): String =
        if (file.exists()) file.readText().takeLast(500) else ""
}
