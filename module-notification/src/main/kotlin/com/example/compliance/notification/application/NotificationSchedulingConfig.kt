package com.example.compliance.notification.application

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/** M18 §5.1：启用 @Scheduled（R-M18-1 单实例重试任务；多实例需迁移持久化调度器 —— spec §2.2 note）。 */
@Configuration
@EnableScheduling
class NotificationSchedulingConfig
