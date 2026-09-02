package com.example.compliance.engineadapter.semgrep

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
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
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroy()
            throw IllegalStateException("semgrep timed out after ${timeoutSeconds}s")
        }
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        return output
    }
}
