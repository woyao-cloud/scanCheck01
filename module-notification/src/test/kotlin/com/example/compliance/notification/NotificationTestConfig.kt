package com.example.compliance.notification

import org.springframework.boot.autoconfigure.SpringBootApplication

/** @WebMvcTest 上下文标记：module-notification 无自身 @SpringBootApplication（app-server 启动类不在本模块
 *  测试类路径上），切片需以此为配置入口 + 组件扫描根（拾取 com.example.compliance.notification 下的 controller）。 */
@SpringBootApplication
class NotificationTestConfig
