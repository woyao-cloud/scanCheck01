package com.example.compliance.engineadapter.gitleaks

import com.example.compliance.result.engine.RawFinding
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitleaksResultParserTest {
    private val parser = GitleaksResultParser()
    private val json = javaClass.getResource("/gitleaks/basic.json").readText(StandardCharsets.UTF_8)

    @Test
    fun `parses leaks into raw findings with native severity`() {
        val findings = parser.parse(json)
        assertEquals(2, findings.size)
        assertEquals("generic-api-key", findings[0].engineRuleId)
        assertEquals("src/main/resources/application.yml", findings[0].filePath)
        assertEquals(12, findings[0].line)
        assertEquals("HIGH", findings[0].severity)                     // 保留原生 severity
        assertEquals("apiKey = \"sk-1234567890abcdef\"", findings[0].codeSnippet)
        assertEquals("aws-access-token", findings[1].engineRuleId)
        assertEquals("", findings[1].severity)                          // 空串原样保留 → normalize 缺省映射
        assertTrue(findings[1].packageName == null)                     // 代码类无依赖字段
    }

    @Test
    fun `empty report yields empty list`() {
        assertTrue(parser.parse("[]").isEmpty())
    }

    @Test
    fun `non-array or invalid json yields empty list`() {
        assertTrue(parser.parse("{}").isEmpty())
        assertTrue(parser.parse("not json").isEmpty())
    }
}
