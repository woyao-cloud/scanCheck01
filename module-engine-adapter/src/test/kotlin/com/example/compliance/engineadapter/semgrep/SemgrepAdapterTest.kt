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
    fun `cleanup clears stdout ref`() {
        every { cli.run(any(), any()) } returns json
        adapter.executeScan(ctx)
        adapter.collectResult(ctx)
        adapter.cleanup(ctx)
        assertTrue(adapter.collectResult(ctx).isEmpty(), "after cleanup collectResult returns empty")
    }

    @Test
    fun `scan target prefers workDir`() {
        every { cli.run(any(), any()) } returns json
        adapter.executeScan(ScanContext(1L, 1L, "https://git.example.com/repo.git", "main", workDir = "/tmp/checkout"))
        verify { cli.run("/tmp/checkout", "main") }
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
