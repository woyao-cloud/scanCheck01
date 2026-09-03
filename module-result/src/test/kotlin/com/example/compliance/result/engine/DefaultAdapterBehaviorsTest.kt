package com.example.compliance.result.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M8：spec §5.1 五方法契约默认行为（P2-D8）——未覆写方法有确定性默认值；scan() 兼容默认跑五阶段管线。 */
class DefaultAdapterBehaviorsTest {

    private class NoopAdapter(override val engine: String = "NOOP") : ScanEngineAdapter

    @Test
    fun `supports is case-insensitive`() {
        val adapter = NoopAdapter("semgrep")
        assertTrue(adapter.supports("SEMGREP"))
        assertTrue(adapter.supports("SemGrep"))
        assertFalse(adapter.supports("trivy"))
    }

    @Test
    fun `default scan returns empty success`() {
        val result = NoopAdapter().scan(ScanContext(1L, 1L, "https://x.git", "main"))
        assertTrue(result.success)
        assertEquals(0, result.findings.size)
    }

    @Test
    fun `executeScan defaults to success and normalizeResult is identity`() {
        val adapter = NoopAdapter()
        val ctx = ScanContext(1L, 1L, "https://x.git")
        assertTrue(adapter.executeScan(ctx).success)
        val raw = listOf(RawFinding("r1", "n", "A.java", 1, "HIGH"))
        assertEquals(raw, adapter.normalizeResult(ctx, raw))
        adapter.cleanup(ctx)   // 不抛异常即通过
    }

    @Test
    fun `scan default runs overridden five-stage methods and aggregates`() {
        var prepared = false
        var cleaned = false
        val adapter = object : ScanEngineAdapter {
            override val engine = "FIVESTAGE"
            override fun prepareScan(context: ScanContext) { prepared = true }
            override fun executeScan(context: ScanContext) = ScanExecutionResult(success = true, durationMs = 7)
            override fun collectResult(context: ScanContext) = listOf(RawFinding("r1", "n", "A.java", 1, "INFO"))
            override fun normalizeResult(context: ScanContext, raw: List<RawFinding>) = raw.map { it.copy(severity = "HIGH") }
            override fun cleanup(context: ScanContext) { cleaned = true }
        }

        val result = adapter.scan(ScanContext(1L, 1L, "https://x.git"))

        assertTrue(prepared)
        assertTrue(cleaned)
        assertEquals("HIGH", result.findings.single().severity)
        assertEquals(7, result.durationMs)
    }
}
