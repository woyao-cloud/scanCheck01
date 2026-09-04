package com.example.compliance.engineadapter.detekt

import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetektResultParserTest {
    private val parser = DetektResultParser()
    private val sarif = javaClass.getResource("/detekt/sarif.json").readText(StandardCharsets.UTF_8)

    @Test
    fun `parses sarif results into code-class findings`() {
        val out = parser.parse(sarif)
        assertEquals(2, out.size)
        val first = out[0]
        assertEquals("MagicNumber", first.engineRuleId)
        assertEquals("src/main/kotlin/com/example/App.kt", first.filePath)
        assertEquals(17, first.line)
        assertEquals("warning", first.severity)          // 原生透传（映射在 normalizeResult）
        assertEquals("This expression contains a magic number", first.message)
        assertEquals("MagicNumber", first.category)      // ruleId 点前缀段
        assertNull(first.packageName)                    // 代码类恒无依赖字段
        val second = out[1]
        assertEquals("LongMethod", second.engineRuleId)
        assertEquals(42, second.line)
        assertEquals("error", second.severity)
    }

    @Test
    fun `invalid or empty input yields empty list`() {
        assertTrue(parser.parse("not json").isEmpty())
        assertTrue(parser.parse("{}").isEmpty())
    }
}
