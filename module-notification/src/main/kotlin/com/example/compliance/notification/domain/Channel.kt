package com.example.compliance.notification.domain

/** 通知渠道（spec §6.4：渠道仍延后 —— 真实发送仅 IN_APP 落库，其余仅日志占位）。 */
enum class Channel { IN_APP, EMAIL, WECHAT, DINGTALK }
