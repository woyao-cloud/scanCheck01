package com.example.compliance.engineadapter.detekt

import com.example.compliance.result.engine.ScanContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetektAdapterTest {
    private val cli = mockk<DetektCli>()
    private val adapter = DetektAdapter(cli, DetektResultParser(), DetektSeverityMapper())

    private val sarif = javaClass.getResource("/detekt/sarif.json").readText(StandardCharsets.UTF_8)
    private val ctx = ScanContext(1L, 1L, "https://git.example.com/repo.git", "main")
    private val failCtx = ScanContext(101L, 1L, "https://git.example.com/repo.git", "main")

    @Test
    fun `execute collect normalize maps severity and keeps code fields`() {
        every { cli.run(any()) } returns sarif
        val execution = adapter.executeScan(ctx)
        assertTrue(execution.success)
        assertTrue(execution.stdoutRef != null)
        val raw = adapter.collectResult(ctx)
        assertEquals(2, raw.size)
        assertEquals("warning", raw[0].severity)
        val normalized = adapter.normalizeResult(ctx, raw)
        assertEquals("MEDIUM", normalized[0].severity)   // warning → MEDIUM
        assertEquals("HIGH", normalized[1].severity)     // error → HIGH
        assertEquals("src/main/kotlin/com/example/App.kt", normalized[0].filePath)
    }

    @Test
    fun `cli failure maps to unsuccessful execution without stdout file`() {
        every { cli.run(any()) } throws IllegalStateException("detekt exited with code 1")
        val execution = adapter.executeScan(failCtx)
        assertTrue(!execution.success)
        assertTrue(execution.stdoutRef == null)          // F1: cli 失败不落盘 stdout
        assertTrue(adapter.collectResult(failCtx).isEmpty())
    }

    @AfterEach
    fun tearDown() {
        adapter.cleanup(ctx)
        adapter.cleanup(failCtx)
    }

    @Test
    fun `cleanup clears stdout file`() {
        every { cli.run(any()) } returns sarif
        adapter.executeScan(ctx)
        adapter.collectResult(ctx)
        adapter.cleanup(ctx)
        assertTrue(adapter.collectResult(ctx).isEmpty())
    }

    @Test
    fun `engine name is DETEKT`() {
        assertEquals("DETEKT", adapter.engine)
    }
}
