package com.example.compliance.engineadapter.dependencycheck

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DependencyCheckSeverityMapperTest {
    private val mapper = DependencyCheckSeverityMapper()

    @Test
    fun `maps native dc severities`() {
        assertEquals("HIGH", mapper.map("High"))
        assertEquals("MEDIUM", mapper.map("Medium"))
        assertEquals("LOW", mapper.map("Low"))
        assertEquals("HIGH", mapper.map("high"))
    }

    @Test
    fun `maps unknown severities to MEDIUM per spec`() {
        assertEquals("MEDIUM", mapper.map("CRITICAL"))     // spec §5.1: only HIGH/MEDIUM/LOW 直通
        assertEquals("MEDIUM", mapper.map("UNKNOWN"))
        assertEquals("MEDIUM", mapper.map(""))
    }
}
