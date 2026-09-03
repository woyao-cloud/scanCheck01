package com.example.compliance.engineadapter.trivy

import com.example.compliance.result.engine.RawFinding
import com.example.compliance.result.engine.ScanContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrivyAdapterTest {
    private val cli = mockk<TrivyCli>()
    private val adapter = TrivyAdapter(cli, TrivyResultParser(), TrivySeverityMapper())

    private val json = javaClass.getResource("/trivy/basic.json").readText(StandardCharsets.UTF_8)
    private val ctx = ScanContext(1L, 1L, "https://git.example.com/repo.git", "main")
    private val failCtx = ScanContext(101L, 1L, "https://git.example.com/repo.git", "main")

    @Test
    fun `execute collect normalize keeps dependency fields and maps severity`() {
        every { cli.run(any()) } returns json
        val execution = adapter.executeScan(ctx)
        assertTrue(execution.success)
        assertTrue(execution.stdoutRef != null)
        val raw = adapter.collectResult(ctx)
        assertEquals(2, raw.size)
        assertEquals("CRITICAL", raw[0].severity)
        assertEquals("lodash", raw[0].packageName)
        val normalized = adapter.normalizeResult(ctx, raw)
        assertEquals("CRITICAL", normalized[0].severity)
        assertEquals("LOW", normalized[1].severity)        // UNKNOWN → LOW
        assertEquals("lodash", normalized[0].packageName)  // 依赖字段原样保留
    }

    @Test
    fun `cli failure maps to unsuccessful execution without stdout file`() {
        every { cli.run(any()) } throws IllegalStateException("trivy exited with code 1")
        val execution = adapter.executeScan(failCtx)
        assertTrue(!execution.success)
        assertTrue(execution.stdoutRef == null)                    // F1: cli 失败不落盘 stdout
        assertTrue(adapter.collectResult(failCtx).isEmpty())
    }

    @AfterEach
    fun tearDown() {
        adapter.cleanup(ctx)
        adapter.cleanup(failCtx)
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
    fun `engine name is TRIVY`() {
        assertEquals("TRIVY", adapter.engine)
    }
}
