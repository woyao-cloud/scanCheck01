package com.example.compliance.common.exception

import com.example.compliance.common.api.ApiResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.servlet.resource.NoResourceFoundException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M9：BusinessException.code 映射 HTTP 状态。 */
class GlobalExceptionHandlerTest {
    private val handler = GlobalExceptionHandler()

    @Test
    fun `business exception code maps to HTTP status with code and message`() {
        val resp = handler.handleBusiness(BusinessException(422, "bad input"))
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.statusCode)
        assertEquals(422, resp.body?.code)
        assertEquals("bad input", resp.body?.message)
    }

    @Test
    fun `code 404 maps to NOT_FOUND`() {
        val resp: ResponseEntity<*> = handler.handleBusiness(BusinessException(404, "nope"))
        assertEquals(HttpStatus.NOT_FOUND, resp.statusCode)
    }

    @Test
    fun `code 409 maps to CONFLICT`() {
        val resp: ResponseEntity<*> = handler.handleBusiness(BusinessException(409, "dup"))
        assertEquals(HttpStatus.CONFLICT, resp.statusCode)
    }

    @Test
    fun `missing resource maps to NOT_FOUND`() {
        val resp: ResponseEntity<*> = handler.handleNotFound(NoResourceFoundException(HttpMethod.GET, "handler"))
        assertEquals(HttpStatus.NOT_FOUND, resp.statusCode)
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
