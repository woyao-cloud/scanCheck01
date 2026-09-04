package com.example.compliance.engineadapter.sonarqube

import com.example.compliance.engineadapter.cli.CliExecutor
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

interface SonarQubeCli {
    fun run(workDir: String, projectKey: String, token: String, serverUrl: String): String
}

/** sonar-scanner CLI（spec §3.2）：四个 -D 属性 + SONAR_TOKEN 环境变量（R-M15-D3，token 不进 argv）。
 *  CliExecutor 无 working-dir → -Dsonar.projectBaseDir=<workDir> + -Dsonar.sources=. 指向检出目录；
 *  mergeErrorStream=true → 合并输出含 CE task URL（R-M15-D9 从中提取）。 */
@Component
class ProcessSonarQubeCli(
    @Value("\${app.sonarqube.timeout-seconds:900}")
    private val timeoutSeconds: Long,
) : SonarQubeCli {
    private val executor = CliExecutor(timeoutSeconds)

    override fun run(workDir: String, projectKey: String, token: String, serverUrl: String): String =
        executor.run(
            command = listOf(
                "sonar-scanner",
                "-Dsonar.projectKey=$projectKey",
                "-Dsonar.host.url=$serverUrl",
                "-Dsonar.projectBaseDir=$workDir",
                "-Dsonar.sources=.",
            ),
            label = "sonar-scanner",
            config = CliExecutor.Config(
                mergeErrorStream = true,
                successExitCodes = setOf(0),
                env = mapOf("SONAR_TOKEN" to token),   // R-M15-D3：token 仅经环境变量，命令列表绝不含 token
            ),
        )
}
