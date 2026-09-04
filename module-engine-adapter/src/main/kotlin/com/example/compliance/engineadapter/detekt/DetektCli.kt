package com.example.compliance.engineadapter.detekt

import com.example.compliance.engineadapter.cli.CliExecutor
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File

interface DetektCli {
    fun run(targetPath: String): String
}

/** Detekt Kotlin 静态分析 CLI（spec §5.2）：--report sarif:<file> 写 SARIF JSON（镜像 Gitleaks resultFile 模式）。 */
@Component
class ProcessDetektCli(
    @Value("\${app.detekt.timeout-seconds:300}")
    private val timeoutSeconds: Long,
) : DetektCli {
    private val executor = CliExecutor(timeoutSeconds)

    override fun run(targetPath: String): String {
        val report = File.createTempFile("detekt-report-", ".sarif")
        return executor.run(
            command = listOf("detekt", "--input", targetPath, "--report", "sarif:${report.absolutePath}"),
            label = "detekt",
            config = CliExecutor.Config(
                mergeErrorStream = false,
                successExitCodes = setOf(0),
                resultFile = report,
            ),
        )
    }
}
