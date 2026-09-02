plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Kotlin 类约定插件需要这些插件实现位于 buildSrc classpath；
    // 它们会以未知版本进入整个构建的 classpath，因此根 build.gradle.kts 不能再带版本请求（见 Step 2）。
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
    implementation("org.jetbrains.kotlin.plugin.spring:org.jetbrains.kotlin.plugin.spring.gradle.plugin:2.0.21")
    implementation("org.jetbrains.kotlin.plugin.jpa:org.jetbrains.kotlin.plugin.jpa.gradle.plugin:2.0.21")
    implementation("io.spring.dependency-management:io.spring.dependency-management.gradle.plugin:1.1.6")
}
