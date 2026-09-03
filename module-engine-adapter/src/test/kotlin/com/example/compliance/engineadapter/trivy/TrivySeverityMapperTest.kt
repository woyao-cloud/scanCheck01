package com.example.compliance.engineadapter.trivy

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TrivySeverityMapperTest {
    private val mapper = TrivySeverityMapper()

    @Test
    fun `passes through unified severities and defaults unknown to low`() {
        assertEquals("CRITICAL", mapper.map("CRITICAL"))
        assertEquals("HIGH", mapper.map("HIGH"))
        assertEquals("MEDIUM", mapper.map("MEDIUM"))
        assertEquals("LOW", mapper.map("LOW"))
        assertEquals("LOW", mapper.map("UNKNOWN"))   // 兜底（与 SemgrepSeverityMapper 的 else->LOW 一致）
        assertEquals("LOW", mapper.map(""))
    }
}
