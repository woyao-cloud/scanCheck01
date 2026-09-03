package com.example.compliance.engineadapter.semgrep

import com.example.compliance.result.engine.ScanContext
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemgrepAdapterTest {
    private val cli = mockk<SemgrepCli>()
    private val adapter = SemgrepAdapter(cli, SemgrepResultParser(), SemgrepSeverityMapper())

    @Test
    fun `scan returns normalized severities`() {
        val json = javaClass.getResource("/semgrep/basic.json").readText(StandardCharsets.UTF_8)
        every { cli.run(any(), any()) } returns json
        val result = adapter.scan(
            ScanContext(1L, 1L, "https://git.example.com/repo.git", "main", configJson = """{"localPath":"/tmp/repo"}""")
        )
        assertTrue(result.success)
        assertEquals(2, result.findings.size)
        assertEquals("HIGH", result.findings[0].severity)
        assertEquals("MEDIUM", result.findings[1].severity)
    }

    @Test
    fun `engine name is SEMGREP`() {
        assertEquals("SEMGREP", adapter.engine)
    }
}
