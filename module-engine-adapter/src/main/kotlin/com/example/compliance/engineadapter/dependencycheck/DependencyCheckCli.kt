package com.example.compliance.engineadapter.dependencycheck

import com.example.compliance.engineadapter.cli.CliExecutor
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files

interface DependencyCheckCli {
    fun run(targetPath: String, scanTaskId: Long): String
}

/** Dependency-Check（OWASP）CLI（spec §5.1）：--format JSON --out 目录写 dependency-check-report.json，
 *  --noupdate 避免每次联网拉 NVD。薄壳：resultFile = --out 目录下报告文件（CliExecutor 读后清理，镜像 Gitleaks resultFile 模式）；
 *  报告 JSON 作为 run 返回值（镜像 Trivy stdout 语义）。 */
@Component
class ProcessDependencyCheckCli(
    @Value("\${app.dependencycheck.timeout-seconds:600}")
    private val timeoutSeconds: Long,
) : DependencyCheckCli {
    private val executor = CliExecutor(timeoutSeconds)

    override fun run(targetPath: String, scanTaskId: Long): String {
        val outDir = Files.createTempDirectory("dependencycheck-out-").toFile()
        val report = File(outDir, "dependency-check-report.json")
        return try {
            executor.run(
                command = listOf("dependency-check", "--project", scanTaskId.toString(),
                    "--scan", targetPath, "--format", "JSON", "--out", outDir.absolutePath, "--noupdate"),
                label = "dependency-check",
                config = CliExecutor.Config(
                    mergeErrorStream = false,
                    successExitCodes = setOf(0),
                    resultFile = report,
                ),
            )
        } finally {
            // CliExecutor 的 finally 已删 resultFile；此处清空 outDir（R-M14-2；若 DC 额外写文件则 delete 失败静默，可接受）
            runCatching { outDir.delete() }
        }
    }
}
