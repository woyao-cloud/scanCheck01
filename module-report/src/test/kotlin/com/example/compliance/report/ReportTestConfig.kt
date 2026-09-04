package com.example.compliance.report

import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * @WebMvcTest 上下文标记：module-report 无自身 @SpringBootApplication
 * （app-server 的启动类不在本模块测试类路径上），切片需以此为配置入口。
 * 用 @SpringBootApplication 而非裸 @SpringBootConfiguration：@WebMvcTest 通过
 * WebMvcTypeExcludeFilter 在组件扫描中拾取指定 controller，裸标记无 @ComponentScan
 * 会导致 controller 永不注册（404）；切片自身的 @OverrideAutoConfiguration(enabled=false)
 * 会关掉本标记携带的 @EnableAutoConfiguration，仅保留 Web 切片自动配置。
 */
@SpringBootApplication
class ReportTestConfig
