package com.example.compliance.engineadapter.trivy

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.util.concurrent.TimeUnit

interface TrivyCli {
    fun run(targetPath: String): String
}

/** trivy CLI 进程封装（spec §6.1）：`trivy fs <target> --format json --no-progress`。
 *  稳健设计同 GitleaksCli：stdout/stderr 各自重定向独立临时文件（无未读管道），JSON 只从 stdout 读取。
 *  exit 语义：0=成功（命中漏洞不改变退出码，默认无 --exit-code）；非 0 抛异常。 */
@Component
class ProcessTrivyCli(
    @Value("\${app.trivy.timeout-seconds:600}")
    private val timeoutSeconds: Long,
) : TrivyCli {
    override fun run(targetPath: String): String {
        val out = File.createTempFile("trivy-out-", ".json")
        val err = File.createTempFile("trivy-err-", ".log")
        try {
            val cmd = listOf("trivy", "fs", targetPath, "--format", "json", "--no-progress")
            val process = ProcessBuilder(cmd)
                .redirectOutput(out)
                .redirectError(err)
                .start()
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
                throw IllegalStateException("trivy timed out after ${timeoutSeconds}s; stderr: ${tailOf(err)}; stdout: ${tailOf(out)}")
            }
            if (process.exitValue() != 0) {
                throw IllegalStateException("trivy exited with code ${process.exitValue()}; stderr: ${tailOf(err)}; stdout: ${tailOf(out)}")
            }
            return out.readText()
        } finally {
            out.delete(); err.delete()
        }
    }

    private fun tailOf(file: File): String =
        if (file.exists()) file.readText().takeLast(500) else ""
}
