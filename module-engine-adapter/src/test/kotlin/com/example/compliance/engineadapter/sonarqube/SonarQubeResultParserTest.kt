package com.example.compliance.engineadapter.sonarqube

import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SonarQubeResultParserTest {
    private val parser = SonarQubeResultParser()
    private val issues = javaClass.getResource("/sonarqube/issues.json").readText(StandardCharsets.UTF_8)

    @Test
    fun `parses issue json into code-class findings`() {
        val out = parser.parse(issues)
        assertEquals(2, out.size)
        val first = out[0]
        assertEquals("java:S1134", first.engineRuleId)                 // engineRuleId = issue.rule
        assertEquals("src/main/java/com/example/App.java", first.filePath) // component 去 "m15app:" 前缀
        assertEquals(17, first.line)
        assertEquals("MAJOR", first.severity)                          // 原生透传（映射在 normalizeResult）
        assertEquals("Remove this use of 'TODO'", first.message)
        assertEquals("CODE_SMELL", first.category)                     // category = issue.type
        assertNull(first.packageName)                                  // 代码类恒无依赖字段（P3-D8）
        val second = out[1]
        assertEquals("java:S2077", second.engineRuleId)
        assertEquals(42, second.line)
        assertEquals("CRITICAL", second.severity)
        assertEquals("VULNERABILITY", second.category)
    }

    @Test
    fun `invalid or empty input yields empty list`() {
        assertTrue(parser.parse("not json").isEmpty())
        assertTrue(parser.parse("{}").isEmpty())
    }
}
