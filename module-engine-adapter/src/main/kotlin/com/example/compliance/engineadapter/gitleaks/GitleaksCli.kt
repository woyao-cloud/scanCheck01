package com.example.compliance.engineadapter.gitleaks

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.TimeUnit

interface GitleaksCli {
    fun run(targetPath: String): String
}

/** gitleaks CLI 进程封装（spec §5.1）：`gitleaks dir <target> --report-format json --report-path <file> --no-banner`。
 *  稳健设计（吸取 R-8.2-b 教训 + 避免 JSON 污染）：
 *    - JSON 报告经 --report-path 直接落盘，stdout/stderr 只承载日志 —— 不被合并进 JSON
 *    - stdout/stderr 各自重定向独立临时文件（redirectErrorStream=false + 双 redirect）→ 无未读管道，不假超时
 *    - exit 语义：0=无泄漏 / 1=有泄漏，均成功（报告已落盘）；其它退出码抛异常（同 F1：绝不产出假干净扫描）
 *    - 报告文件不存在（gitleaks 旧版无泄漏时不写）→ 返回 "[]"，parser 兼容 */
@Component
class ProcessGitleaksCli(
    @Value("\${app.gitleaks.timeout-seconds:300}")
    private val timeoutSeconds: Long,
) : GitleaksCli {
    override fun run(targetPath: String): String {
        val report = File.createTempFile("gitleaks-report-", ".json")
        val out = File.createTempFile("gitleaks-out-", ".log")
        val err = File.createTempFile("gitleaks-err-", ".log")
        try {
            val cmd = listOf("gitleaks", "dir", targetPath,
                "--report-format", "json", "--report-path", report.absolutePath, "--no-banner")
            val process = ProcessBuilder(cmd)
                .redirectOutput(out)
                .redirectError(err)
                .start()
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
                throw IllegalStateException("gitleaks timed out after ${timeoutSeconds}s")
            }
            val code = process.exitValue()
            if (code != 0 && code != 1) {
                throw IllegalStateException("gitleaks exited with code $code")
            }
            return if (report.exists()) report.readText() else "[]"
        } finally {
            report.delete(); out.delete(); err.delete()
        }
    }
}
