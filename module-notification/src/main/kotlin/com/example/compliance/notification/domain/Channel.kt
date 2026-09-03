package com.example.compliance.notification.domain

/** 通知渠道（spec §6.4：渠道投递仍延后 —— 所有渠道均以占位方式落库（站内信表预留）+ 日志，无真实渠道投递）。 */
enum class Channel { IN_APP, EMAIL, WECHAT, DINGTALK }
