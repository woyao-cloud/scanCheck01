package com.example.compliance.common.api

/** 统一分页响应（spec 统一 API：{items,page,size,total}）。 */
data class PageView<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)
