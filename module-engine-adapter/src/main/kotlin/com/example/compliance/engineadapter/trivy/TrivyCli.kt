package com.example.compliance.engineadapter.trivy

import com.example.compliance.engineadapter.cli.CliExecutor
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

interface TrivyCli {
    fun run(targetPath: String): String
}

@Component
class ProcessTrivyCli(
    @Value("\${app.trivy.timeout-seconds:600}")
    private val timeoutSeconds: Long,
) : TrivyCli {
    private val executor = CliExecutor(timeoutSeconds)
    override fun run(targetPath: String): String =
        executor.run(
            command = listOf("trivy", "fs", targetPath, "--format", "json", "--no-progress"),
            label = "trivy",
            config = CliExecutor.Config(
                mergeErrorStream = false,
                successExitCodes = setOf(0),
                includeStdoutTail = true,
            ),
        )
}
