package com.example.compliance.notification.domain

/** 通知模板版本状态（本地枚举，镜像 checklist VersionStatus 值语义；notification 不依赖 checklist —— R-M18-3）。 */
enum class TemplateStatus { DRAFT, PUBLISHED, DISABLED }
