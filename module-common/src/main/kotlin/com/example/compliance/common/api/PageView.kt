package com.example.compliance.common.api

/**
 * 统一分页响应（spec §6.2 命名 `PageView<T>`）。与既有 `PageResponse` 同形
 * （{items,page,size,total}）—— 别名复用同一底层数据类，避免重复类型
 * （M10 ruling：spec 统一 API 形状已由 `PageResponse` 承载，`PageView` 仅为 spec 命名视角）。
 */
typealias PageView<T> = PageResponse<T>
