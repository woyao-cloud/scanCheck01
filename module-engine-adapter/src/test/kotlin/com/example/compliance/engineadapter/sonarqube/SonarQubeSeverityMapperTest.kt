package com.example.compliance.engineadapter.sonarqube

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SonarQubeSeverityMapperTest {
    private val mapper = SonarQubeSeverityMapper()

    @Test
    fun `maps native sonarqube severities`() {
        assertEquals("CRITICAL", mapper.map("BLOCKER"))   // R-M15-D6：首个可达 CRITICAL 的引擎
        assertEquals("HIGH", mapper.map("CRITICAL"))
        assertEquals("MEDIUM", mapper.map("MAJOR"))
        assertEquals("LOW", mapper.map("MINOR"))
        assertEquals("LOW", mapper.map("INFO"))
    }

    @Test
    fun `maps unknown severities to LOW`() {
        assertEquals("LOW", mapper.map("UNKNOWN"))
        assertEquals("LOW", mapper.map(""))
    }
}
