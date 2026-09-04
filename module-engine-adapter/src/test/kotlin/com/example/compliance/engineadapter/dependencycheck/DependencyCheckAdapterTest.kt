package com.example.compliance.engineadapter.dependencycheck

import com.example.compliance.result.engine.ScanContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyCheckAdapterTest {
    private val cli = mockk<DependencyCheckCli>()
    private val adapter = DependencyCheckAdapter(cli, DependencyCheckResultParser(), DependencyCheckSeverityMapper())

    private val report = javaClass.getResource("/dependencycheck/basic.json").readText(StandardCharsets.UTF_8)
    private val ctx = ScanContext(1L, 1L, "https://git.example.com/repo.git", "main")
    private val failCtx = ScanContext(101L, 1L, "https://git.example.com/repo.git", "main")

    @Test
    fun `execute collect normalize keeps dependency fields and maps severity`() {
        every { cli.run(any(), any()) } returns report
        val execution = adapter.executeScan(ctx)
        assertTrue(execution.success)
        assertTrue(execution.stdoutRef != null)
        val raw = adapter.collectResult(ctx)
        assertEquals(2, raw.size)
        assertEquals("High", raw[0].severity)          // 原生透传
        assertEquals("lodash", raw[0].packageName)
        val normalized = adapter.normalizeResult(ctx, raw)
        assertEquals("HIGH", normalized[0].severity)   // High → HIGH
        assertEquals("MEDIUM", normalized[1].severity) // Medium → MEDIUM
        assertEquals("lodash", normalized[0].packageName)  // 依赖字段原样保留
    }

    @Test
    fun `cli failure maps to unsuccessful execution without stdout file`() {
        every { cli.run(any(), any()) } throws IllegalStateException("dependency-check exited with code 1")
        val execution = adapter.executeScan(failCtx)
        assertTrue(!execution.success)
        assertTrue(execution.stdoutRef == null)        // F1: cli 失败不落盘 stdout
        assertTrue(adapter.collectResult(failCtx).isEmpty())
    }

    @AfterEach
    fun tearDown() {
        adapter.cleanup(ctx)
        adapter.cleanup(failCtx)
    }

    @Test
    fun `cleanup clears stdout file`() {
        every { cli.run(any(), any()) } returns report
        adapter.executeScan(ctx)
        adapter.collectResult(ctx)
        adapter.cleanup(ctx)
        assertTrue(adapter.collectResult(ctx).isEmpty())
    }

    @Test
    fun `engine name is DEPENDENCYCHECK`() {
        assertEquals("DEPENDENCYCHECK", adapter.engine)
    }
}
