package com.example.compliance.engineadapter.sonarqube

import com.example.compliance.result.engine.ScanContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 适配器测试镜像 DetektAdapterTest + 服务端型失败路径（R-M15-D1 三段：scanner → CE 轮询 → issues 拉取）。 */
class SonarQubeAdapterTest {
    private val cli = mockk<SonarQubeCli>()
    private val apiClient = mockk<SonarQubeApiClient>()
    private val adapter = SonarQubeAdapter(cli, apiClient, SonarQubeResultParser(), SonarQubeSeverityMapper(), "http://sq:9000", 900)
    private val fastAdapter = SonarQubeAdapter(cli, apiClient, SonarQubeResultParser(), SonarQubeSeverityMapper(), "http://sq:9000", 1, pollIntervalMs = 10)  // R-M15-1：短轮询间隔使超时测试快

    private val issues = javaClass.getResource("/sonarqube/issues.json").readText(StandardCharsets.UTF_8)
    private val taskUrl = "More about the report processing at http://sq:9000/api/ce/task?id=AX123"
    private val ctx = ScanContext(1L, 1L, "https://git.example.com/m15app.git", "main", workDir = "/tmp/scan", credentialToken = "tok")
    private val failCtx = ScanContext(101L, 1L, "https://git.example.com/m15app.git", "main", workDir = "/tmp/scan", credentialToken = "tok")
    private val noCredCtx = ScanContext(201L, 1L, "https://git.example.com/m15app.git", "main", workDir = "/tmp/scan", credentialToken = null)

    @Test
    fun `execute collect normalize maps severity and keeps code fields`() {
        // R-M15-4：断言 cli 参数逐字（projectKey 派生自 repoUrl + token 传递 + serverUrl）
        every { cli.run(any(), "m15app", "tok", "http://sq:9000") } returns taskUrl
        every { apiClient.ceTaskStatus(any(), "AX123", "tok") } returns "SUCCESS"
        every { apiClient.issues("http://sq:9000", "m15app", "tok") } returns issues
        val execution = adapter.executeScan(ctx)
        assertTrue(execution.success)
        assertTrue(execution.stdoutRef != null)
        val raw = adapter.collectResult(ctx)
        assertEquals(2, raw.size)
        assertEquals("MAJOR", raw[0].severity)               // 原生透传
        val normalized = adapter.normalizeResult(ctx, raw)
        assertEquals("MEDIUM", normalized[0].severity)       // MAJOR → MEDIUM
        assertEquals("HIGH", normalized[1].severity)         // CRITICAL → HIGH
        assertEquals("src/main/java/com/example/App.java", normalized[0].filePath)
    }

    @Test
    fun `missing credential yields unsuccessful execution without stdout file`() {
        val execution = adapter.executeScan(noCredCtx)
        assertTrue(!execution.success)
        assertTrue(execution.stdoutRef == null)              // F1: 失败不落盘 stdout
    }

    @Test
    fun `cli failure maps to unsuccessful execution without stdout file`() {
        every { cli.run(any(), any(), any(), any()) } throws IllegalStateException("sonar-scanner exited with code 1")
        val execution = adapter.executeScan(failCtx)
        assertTrue(!execution.success)
        assertTrue(execution.stdoutRef == null)
        assertTrue(adapter.collectResult(failCtx).isEmpty())
    }

    @Test
    fun `missing ce task id yields unsuccessful execution without stdout file`() {
        every { cli.run(any(), any(), any(), any()) } returns "analysis finished without task id"
        val execution = adapter.executeScan(failCtx)
        assertTrue(!execution.success)
        assertTrue(execution.stdoutRef == null)
    }

    @Test
    fun `ce task failure yields unsuccessful execution without stdout file`() {
        every { cli.run(any(), any(), any(), any()) } returns taskUrl
        every { apiClient.ceTaskStatus(any(), any(), any()) } returns "FAILED"
        val execution = adapter.executeScan(failCtx)
        assertTrue(!execution.success)
        assertTrue(execution.stdoutRef == null)
    }

    @Test
    fun `ce task timeout yields unsuccessful execution without stdout file`() {
        every { cli.run(any(), any(), any(), any()) } returns taskUrl
        every { apiClient.ceTaskStatus(any(), any(), any()) } returns "IN_PROGRESS"
        val execution = fastAdapter.executeScan(failCtx)   // 1s 总超时 + 10ms 轮询（R-M15-1）
        assertTrue(!execution.success)
        assertTrue(execution.stdoutRef == null)
    }

    @AfterEach
    fun tearDown() {
        adapter.cleanup(ctx)
        adapter.cleanup(failCtx)
        adapter.cleanup(noCredCtx)
    }

    @Test
    fun `cleanup clears stdout file`() {
        every { cli.run(any(), "m15app", "tok", "http://sq:9000") } returns taskUrl
        every { apiClient.ceTaskStatus(any(), any(), any()) } returns "SUCCESS"
        every { apiClient.issues(any(), any(), any()) } returns issues
        adapter.executeScan(ctx)
        adapter.collectResult(ctx)
        adapter.cleanup(ctx)
        assertTrue(adapter.collectResult(ctx).isEmpty())
    }

    @Test
    fun `engine name is SONARQUBE`() {
        assertEquals("SONARQUBE", adapter.engine)
    }
}
