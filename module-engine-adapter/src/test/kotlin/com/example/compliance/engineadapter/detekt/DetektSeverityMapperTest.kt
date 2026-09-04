package com.example.compliance.engineadapter.detekt

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DetektSeverityMapperTest {
    private val mapper = DetektSeverityMapper()

    @Test
    fun `maps native detekt severities`() {
        assertEquals("HIGH", mapper.map("error"))
        assertEquals("MEDIUM", mapper.map("warning"))
        assertEquals("LOW", mapper.map("info"))
    }

    @Test
    fun `maps unknown severities to LOW`() {
        assertEquals("LOW", mapper.map("UNKNOWN"))
        assertEquals("LOW", mapper.map("note"))
        assertEquals("LOW", mapper.map(""))
    }
}
