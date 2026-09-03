package com.example.compliance.project.application

/** admin 项目计数端口（P2-D5：跨模块只暴露接口/值类型，禁止 @Entity）。 */
interface ProjectQueryPort {
    fun count(): Long
}
