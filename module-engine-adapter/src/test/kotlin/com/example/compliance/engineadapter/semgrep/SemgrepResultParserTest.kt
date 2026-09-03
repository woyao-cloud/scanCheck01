package com.example.compliance.engineadapter.semgrep

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

    @Test
    fun `error payload with results empty is rejected`() {
        // F1 (final review C1): {"errors":[...],"results":[]} 是 semgrep 配置/规则错误形态，
        // 必须抛异常而非解析成 0 finding 的「干净扫描」。
        val payload = """{"errors":[{"code":2,"message":"invalid rule config"}],"results":[]}"""
        assertThrows<IllegalArgumentException> { parser.parse(payload) }
    }

    @Test
    fun `clean no-findings payload parses to empty list`() {
        // F1：真正的干净扫描（errors 为空数组 + results 为空）仍是合法结果 → 空列表，非失败。
        val payload = """{"errors":[],"results":[]}"""
        assertEquals(emptyList(), parser.parse(payload))
    }
}
