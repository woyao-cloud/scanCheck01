package com.example.compliance.common.exception

import com.example.compliance.common.api.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /** BusinessException.code → HTTP 状态；未识别 code 回退 400。 */
    @ExceptionHandler(BusinessException::class)
    fun handleBusiness(e: BusinessException): ResponseEntity<ApiResponse<Unit>> =
        // RuntimeException.message 为 String?，需兜底
        ResponseEntity.status(httpStatusFor(e.code)).body(ApiResponse.error(e.code, e.message ?: "business error"))

    // Task 9.3 (method-level RBAC): @PreAuthorize denials throw AccessDeniedException inside the
    // controller invocation; the catch-all @ExceptionHandler(Exception::class) below would otherwise
    // swallow it into a 500. This specific handler maps method-security denials to 403 (spec §6.1).
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(e: AccessDeniedException): ResponseEntity<ApiResponse<Unit>> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, "access denied"))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Unit>> {
        val message = e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(400, message))
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNotFound(e: NoResourceFoundException): ResponseEntity<ApiResponse<Unit>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(404, "resource not found"))

    @ExceptionHandler(Exception::class)
    fun handleUnknown(e: Exception): ResponseEntity<ApiResponse<Unit>> {
        log.error("Unhandled exception", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(500, "internal error"))
    }

    /** BusinessException.code → HTTP 状态；未识别 code 回退 400（resolve 对未知 code 返回 null）。 */
    private fun httpStatusFor(code: Int): HttpStatus = when (code) {
        400 -> HttpStatus.BAD_REQUEST
        401 -> HttpStatus.UNAUTHORIZED
        403 -> HttpStatus.FORBIDDEN
        404 -> HttpStatus.NOT_FOUND
        409 -> HttpStatus.CONFLICT
        500 -> HttpStatus.INTERNAL_SERVER_ERROR
        else -> HttpStatus.resolve(code) ?: HttpStatus.BAD_REQUEST
    }
}
