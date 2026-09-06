package com.example.compliance.notification.domain

/** 通知渠道（spec M17 §3.2/D4）：WEBHOOK 为 M17 新增（VARCHAR(16) 容纳，无 DDL）。WECHAT/DINGTALK 仍为枚举预留。 */
enum class Channel { IN_APP, EMAIL, WECHAT, DINGTALK, WEBHOOK }
