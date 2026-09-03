package com.example.compliance.engineadapter.gitleaks

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitleaksAdapterTest {
    private val cli = mockk<GitleaksCli>()
    private val adapter = GitleaksAdapter(cli, GitleaksResultParser(), GitleaksSeverityMapper())

    private val json = javaClass.getResource("/gitleaks/basic.json").readText(StandardCharsets.UTF_8)
    private val ctx = ScanContext(1L, 1L, "https://git.example.com/repo.git", "main")

    @Test
    fun `execute and collect keep raw severities, normalize maps them`() {
        every { cli.run(any()) } returns json
        val execution = adapter.executeScan(ctx)
        assertTrue(execution.success)
        assertTrue(execution.stdoutRef != null)
        val raw = adapter.collectResult(ctx)
        assertEquals(2, raw.size)
        assertEquals("HIGH", raw[0].severity)
        assertEquals("", raw[1].severity)
        val normalized = adapter.normalizeResult(ctx, raw)
        assertEquals("HIGH", normalized[0].severity)
        assertEquals("MEDIUM", normalized[1].severity)   // 空串 → 缺省 MEDIUM
    }

    @Test
    fun `cli failure maps to unsuccessful execution without writing stdout file`() {
        every { cli.run(any()) } throws IllegalStateException("gitleaks exited with code 126")
        val execution = adapter.executeScan(ctx)
        assertTrue(!execution.success)
        assertEquals("gitleaks exited with code 126", execution.errorMessage)
        assertTrue(adapter.collectResult(ctx).isEmpty())
    }

    @Test
    fun `cleanup clears stdout file`() {
        every { cli.run(any()) } returns json
        adapter.executeScan(ctx)
        adapter.collectResult(ctx)
        adapter.cleanup(ctx)
        assertTrue(adapter.collectResult(ctx).isEmpty())
    }

    @Test
    fun `scans workdir when present and falls back to repo url`() {
        every { cli.run("/tmp/w") } returns "[]"
        adapter.executeScan(ScanContext(1L, 1L, "https://git.example.com/repo.git", "main", workDir = "/tmp/w"))
        verify { cli.run("/tmp/w") }

        every { cli.run("https://git.example.com/repo.git") } returns "[]"
        adapter.executeScan(ctx)
        verify { cli.run("https://git.example.com/repo.git") }
    }

    @Test
    fun `engine name is GITLEAKS`() {
        assertEquals("GITLEAKS", adapter.engine)
    }
}
