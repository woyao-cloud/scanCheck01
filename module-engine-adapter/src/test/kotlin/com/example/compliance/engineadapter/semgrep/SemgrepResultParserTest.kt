package com.example.compliance.engineadapter.semgrep

import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals

class SemgrepResultParserTest {
    private val parser = SemgrepResultParser()

    @Test
    fun `parses two findings from fixture json`() {
        val json = javaClass.getResource("/semgrep/basic.json")
            .readText(StandardCharsets.UTF_8)
        val findings = parser.parse(json)
        assertEquals(2, findings.size)
        assertEquals("java.lang.security.audit.sql-injection", findings[0].engineRuleId)
        assertEquals("src/main/java/com/demo/OrderDao.java", findings[0].filePath)
        assertEquals(42, findings[0].line)
        assertEquals("ERROR", findings[0].severity)
        assertEquals("Detected potential SQL injection.", findings[0].message)
    }
}
