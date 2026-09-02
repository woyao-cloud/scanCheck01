package com.example.compliance.common.exception

class BusinessException(
    val code: Int = 400,
    message: String,
) : RuntimeException(message)
