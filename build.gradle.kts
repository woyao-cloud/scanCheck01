plugins {
    // kotlin.jvm/spring/jpa 与 io.spring.dependency-management 由约定插件 (buildSrc) 无版本应用——
    // buildSrc 的 implementation 依赖让这些插件以未知版本出现在整个构建 classpath 上，
    // 此处再带版本请求会报 "already on the classpath with an unknown version"。
    // 仅 spring-boot 插件（不在 buildSrc classpath 上）在此 apply false 声明版本。
    alias(libs.plugins.spring.boot) apply false
}
