package com.example.compliance.engineadapter.gitleaks

import com.example.compliance.engineadapter.cli.CliExecutor
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File

interface GitleaksCli {
    fun run(targetPath: String): String
}

@Component
class ProcessGitleaksCli(
    @Value("\${app.gitleaks.timeout-seconds:300}")
    private val timeoutSeconds: Long,
) : GitleaksCli {
    private val executor = CliExecutor(timeoutSeconds)
    override fun run(targetPath: String): String {
        val report = File.createTempFile("gitleaks-report-", ".json")
        return executor.run(
            command = listOf("gitleaks", "dir", targetPath,
                "--report-format", "json", "--report-path", report.absolutePath, "--no-banner"),
            label = "gitleaks",
            config = CliExecutor.Config(
                mergeErrorStream = false,
                successExitCodes = setOf(0, 1),
                resultFile = report,
            ),
        )
    }
}
