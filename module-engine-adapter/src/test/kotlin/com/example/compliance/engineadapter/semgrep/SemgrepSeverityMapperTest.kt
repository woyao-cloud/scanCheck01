package com.example.compliance.engineadapter.semgrep

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SemgrepSeverityMapperTest {
    private val mapper = SemgrepSeverityMapper()

    @Test
    fun `maps semgrep severities to unified levels`() {
        assertEquals("HIGH", mapper.map("ERROR"))
        assertEquals("MEDIUM", mapper.map("WARNING"))
        assertEquals("LOW", mapper.map("INFO"))
        assertEquals("LOW", mapper.map("UNKNOWN"))
    }
}
