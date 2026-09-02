package com.example.compliance.common.exception

import com.example.compliance.common.api.ApiResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MethodArgumentNotValidException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalExceptionHandlerTest {
    private val handler = GlobalExceptionHandler()

    @Test
    fun `business exception becomes 400 with code and message`() {
        val resp = handler.handleBusiness(BusinessException(422, "bad input"))
        assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
        assertEquals(422, resp.body?.code)
        assertEquals("bad input", resp.body?.message)
    }

    @Test
    fun `validation exception aggregates field errors`() {
        val ex = mockk<MethodArgumentNotValidException>()
        val fieldError = mockk<org.springframework.validation.FieldError>()
        every { ex.bindingResult.fieldErrors } returns listOf(fieldError)
        every { fieldError.field } returns "name"
        every { fieldError.defaultMessage } returns "must not be blank"
        val resp = handler.handleValidation(ex)
        assertTrue(resp.body!!.message.contains("name"))
    }

    @Test
    fun `unknown exception becomes 500 without leaking detail`() {
        val resp = handler.handleUnknown(RuntimeException("secret db password=xx"))
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.statusCode)
        assertEquals("internal error", resp.body?.message)
        assertTrue(!resp.body!!.message.contains("secret"))
    }
}
