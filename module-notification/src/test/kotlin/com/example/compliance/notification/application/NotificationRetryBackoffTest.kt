package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Channel
import com.example.compliance.notification.domain.Notification
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationRetryBackoffTest {
    private val backoff = NotificationRetryBackoff(maxAttempts = 5)

    private fun row() = Notification().apply {
        id = 1L; channel = Channel.EMAIL.name; recipient = "a@x.com"; type = "SCAN_COMPLETED"; status = "PENDING"
    }

    private fun assertWindow(actual: Instant?, seconds: Long) {
        val expected = Instant.now().plusSeconds(seconds)
        assertTrue(actual!!.isAfter(expected.minusSeconds(2)) && actual.isBefore(expected.plusSeconds(2)),
            "expected ~now+${seconds}s, got $actual")
    }

    @Test
    fun `onFailure sets failed increments retry and schedules exponential backoff`() {
        val r = row()
        backoff.onFailure(r, "smtp down")
        assertEquals("FAILED", r.status)
        assertEquals(1, r.retryCount)
        assertEquals("smtp down", r.errorMessage)
        assertWindow(r.nextRetryAt, 60)   // 2^(1-1) 分钟
    }

    @Test
    fun `second failure doubles the backoff window`() {
        val r = row()
        backoff.onFailure(r, "m1")
        backoff.onFailure(r, "m2")
        assertEquals(2, r.retryCount)
        assertWindow(r.nextRetryAt, 120)   // 2^(2-1) 分钟
    }

    @Test
    fun `max attempts reached stops rescheduling and annotates`() {
        val limited = NotificationRetryBackoff(maxAttempts = 2)
        val r = row()
        limited.onFailure(r, "m1")
        limited.onFailure(r, "m2")
        assertEquals(2, r.retryCount)
        assertNull(r.nextRetryAt)
        assertTrue(r.errorMessage!!.contains("max attempts reached"))
    }

    @Test
    fun `error message is truncated to 500 chars`() {
        val r = row()
        backoff.onFailure(r, "x".repeat(600))
        assertEquals(500, r.errorMessage!!.length)
    }
}
