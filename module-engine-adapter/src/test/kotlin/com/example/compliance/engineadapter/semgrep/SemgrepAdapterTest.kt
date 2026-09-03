package com.example.compliance.engineadapter.semgrep

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8：SemgrepAdapter 五方法化——execute/collect/normalize/cleanup 职责分离。 */
class SemgrepAdapterTest {
    private val cli = mockk<SemgrepCli>()
    private val adapter = SemgrepAdapter(cli, SemgrepResultParser(), SemgrepSeverityMapper())

    private val json = javaClass.getResource("/semgrep/basic.json").readText(StandardCharsets.UTF_8)
    private val ctx = ScanContext(1L, 1L, "https://git.example.com/repo.git", "main")

    @Test
    fun `execute and collect keep raw severities, normalize maps them`() {
        every { cli.run(any(), any()) } returns json

        val execution = adapter.executeScan(ctx)
        assertTrue(execution.success)
        assertTrue(execution.stdoutRef != null, "stdoutRef should point at persisted output")

        val raw = adapter.collectResult(ctx)
        assertEquals(2, raw.size)
        assertEquals("ERROR", raw[0].severity)   // collectResult 保留原始 severity
        assertEquals("WARNING", raw[1].severity)

        val normalized = adapter.normalizeResult(ctx, raw)
        assertEquals("HIGH", normalized[0].severity)
        assertEquals("MEDIUM", normalized[1].severity)
    }

    @Test
    fun `normalizeResult maps severity only`() {
        val normalized = adapter.normalizeResult(
            ctx,
            listOf(RawFinding("r1", null, "A.java", 1, "INFO", null, null, null)),
        )
        assertEquals("LOW", normalized[0].severity)
    }

    @Test
    fun `cleanup clears stdout file`() {
        every { cli.run(any(), any()) } returns json
        adapter.executeScan(ctx)
        adapter.collectResult(ctx)
        adapter.cleanup(ctx)
        assertTrue(adapter.collectResult(ctx).isEmpty(), "after cleanup collectResult returns empty")
    }

    @Test
    fun `cli failure maps to unsuccessful execution without writing stdout file`() {
        // F1 (final review C1): semgrep exit>=2 → SemgrepCli 抛异常 → executeScan 必须返回
        // success=false + errorMessage，且不落盘 stdout（collectResult 读不到任何东西）。
        every { cli.run(any(), any()) } throws IllegalStateException("semgrep exited with code 2")

        val execution = adapter.executeScan(ctx)

        assertTrue(!execution.success, "cli failure must yield success=false")
        assertEquals("semgrep exited with code 2", execution.errorMessage)
        assertTrue(adapter.collectResult(ctx).isEmpty(), "no stdout file written on failure")
    }

    @Test
    fun `two scans with different task ids do not cross-talk`() {
        // F2 (final review I7): stdout 文件由 scanTaskId 派生，两并发扫描（不同任务）读写互不污染，
        // 一方 cleanup 不影响另一方。
        val jsonEmpty = """{"errors":[],"results":[]}"""
        val ctxA = ScanContext(101L, 1L, "https://git.example.com/repo.git", "main", workDir = "/tmp/a")
        val ctxB = ScanContext(202L, 2L, "https://git.example.com/repo.git", "main", workDir = "/tmp/b")
        every { cli.run("/tmp/a", "main") } returns json
        every { cli.run("/tmp/b", "main") } returns jsonEmpty

        val exA = adapter.executeScan(ctxA)
        val exB = adapter.executeScan(ctxB)
        assertTrue(exA.success && exB.success)
        assertTrue(exA.stdoutRef != exB.stdoutRef, "per-task stdout files must be distinct")

        // 交错：A 有 2 finding、B 0 finding；清理 A 不影响 B
        assertEquals(2, adapter.collectResult(ctxA).size)
        assertEquals(0, adapter.collectResult(ctxB).size)
        adapter.cleanup(ctxA)
        assertTrue(adapter.collectResult(ctxA).isEmpty())
        assertEquals(0, adapter.collectResult(ctxB).size, "B must be unaffected by A's cleanup")
    }

    @Test
    fun `scan default pipeline returns normalized findings`() {
        every { cli.run(any(), any()) } returns json
        val result = adapter.scan(ctx)
        assertTrue(result.success)
        assertEquals(2, result.findings.size)
        assertEquals("HIGH", result.findings[0].severity)
    }

    @Test
    fun `engine name is SEMGREP`() {
        assertEquals("SEMGREP", adapter.engine)
    }
}
