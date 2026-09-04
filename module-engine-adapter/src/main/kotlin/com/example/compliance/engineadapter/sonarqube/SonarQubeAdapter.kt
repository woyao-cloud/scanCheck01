package com.example.compliance.engineadapter.sonarqube

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import com.example.compliance.result.engine.ScanEngineAdapter
import com.example.compliance.result.engine.ScanExecutionResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File

/** SonarQube 适配器（spec §3.4，R-M15-D1/D5/D9）：五方法镜像 DetektAdapter（代码类 stdout-file 语义）。
 *  executeScan 三段：① cli 跑 sonar-scanner（SONAR_TOKEN env）→ ② 提取 CE taskId + 轮询至 SUCCESS/FAILED/CANCELED/超时
 *  → ③ 拉取 issue JSON 落盘 stdout 文件。F1：任一失败 success=false 不落盘（绝不产出假干净扫描）。 */
@Component
class SonarQubeAdapter(
    private val cli: SonarQubeCli,
    private val apiClient: SonarQubeApiClient,
    private val parser: SonarQubeResultParser,
    private val severityMapper: SonarQubeSeverityMapper,
    @Value("\${app.sonarqube.server-url:http://localhost:9000}") private val serverUrl: String,
    @Value("\${app.sonarqube.timeout-seconds:900}") private val timeoutSeconds: Long,
    private val pollIntervalMs: Long = 5_000,   // R-M15-1：轮询间隔可注（测试缩短）
) : ScanEngineAdapter {

    override val engine: String = "SONARQUBE"

    override fun prepareScan(context: ScanContext) {}

    override fun executeScan(context: ScanContext): ScanExecutionResult {
        val start = System.currentTimeMillis()
        return runCatching {
            val token = context.credentialToken
                ?: throw IllegalStateException("sonarqube credential missing for project ${context.projectId}")
            val workDir = context.workDir
                ?: throw IllegalStateException("sonarqube requires a checkout workDir")
            val projectKey = projectKeyOf(context)
            val output = cli.run(workDir, projectKey, token, serverUrl)
            val taskId = extractTaskId(output)
                ?: throw IllegalStateException("sonarqube ce task id not found in scanner output")
            awaitCeTask(taskId, token, start)
            val issues = apiClient.issues(serverUrl, projectKey, token)
            val file = stdoutFile(context)
            file.writeText(issues)
            ScanExecutionResult(success = true, stdoutRef = file.absolutePath)
        }.getOrElse { e ->
            ScanExecutionResult(success = false, errorMessage = e.message, durationMs = System.currentTimeMillis() - start)
        }
    }

    override fun collectResult(context: ScanContext): List<RawFinding> {
        val file = stdoutFile(context)
        if (!file.exists()) return emptyList()
        return parser.parse(runCatching { file.readText() }.getOrDefault("{}"))
    }

    override fun normalizeResult(context: ScanContext, raw: List<RawFinding>): List<RawFinding> =
        raw.map { it.copy(severity = severityMapper.map(it.severity)) }

    override fun cleanup(context: ScanContext) {
        runCatching { stdoutFile(context).delete() }
    }

    private fun awaitCeTask(taskId: String, token: String, start: Long) {
        val deadline = start + timeoutSeconds * 1000
        while (true) {
            val status = apiClient.ceTaskStatus(serverUrl, taskId, token)
            if (status == "SUCCESS") return
            if (status == "FAILED" || status == "CANCELED") {
                throw IllegalStateException("sonarqube ce task $status (taskId=$taskId)")
            }
            if (System.currentTimeMillis() >= deadline) {
                throw IllegalStateException("sonarqube ce task timed out after ${timeoutSeconds}s (taskId=$taskId)")
            }
            Thread.sleep(pollIntervalMs)
        }
    }

    private fun projectKeyOf(context: ScanContext): String =
        context.repoUrl.substringAfterLast("/").removeSuffix(".git")
            .ifBlank { throw IllegalStateException("cannot derive sonarqube project key from repoUrl: ${context.repoUrl}") }

    private fun extractTaskId(output: String): String? =
        Regex("ce/task\\?id=([A-Za-z0-9_-]+)").find(output)?.groupValues?.get(1)

    private fun stdoutFile(context: ScanContext): File =
        File(System.getProperty("java.io.tmpdir"), "sonarqube-stdout-${context.scanTaskId}.json")
}
