package com.example.compliance.common.api

data class ApiResponse<T>(
    val code: Int,
    val message: String,
    val data: T? = null,
) {
    companion object {
        fun <T> ok(data: T): ApiResponse<T> = ApiResponse(0, "success", data)
        fun ok(): ApiResponse<Unit> = ApiResponse(0, "success", null)
        fun <T> error(code: Int, message: String): ApiResponse<T> = ApiResponse(code, message, null)
    }
}
