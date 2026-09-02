package com.example.compliance

import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * 模块约定插件：为每个业务模块统一配置 Kotlin JVM/Spring/JPA、
 * JDK 21 toolchain、Spring Boot BOM 与 JUnit5 + MockK 测试依赖。
 *
 * 依赖版本通过 VersionCatalogsExtension 从主构建默认 libs 目录编程式解析
 * （避免预编译脚本插件无法使用目录访问器 / plugins 块不能带版本的限制）。
 */
class ComplianceKotlinModulePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply("java-library")  // 提供 api() 配置（module-common 用 api 暴露共享技术栈）
        project.plugins.apply("org.jetbrains.kotlin.jvm")
        project.plugins.apply("org.jetbrains.kotlin.plugin.spring")
        project.plugins.apply("org.jetbrains.kotlin.plugin.jpa")
        project.plugins.apply("io.spring.dependency-management")

        project.extensions.configure<JavaPluginExtension> {
            toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
        }
        project.extensions.configure<KotlinJvmProjectExtension> {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        }

        // Spring Boot 无版本坐标（starter 系列）依赖 BOM 提供版本。
        // 注意：imports 的 lambda 是接收者类型 ImportsHandler.() -> Unit，
        // 写 it.mavenBom(...) 会报 Unresolved reference: it。
        project.extensions.configure<DependencyManagementExtension> {
            imports { mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.5") }
        }

        val libs = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
        // kotlin-reflect: Spring Data JPA needs Kotlin reflection to map Kotlin entities at
        // runtime; without it bootRun dies with NoClassDefFoundError kotlin/reflect/full/KClasses
        // (it used to leak in via MockK on the test classpath only — see Ruling #16).
        project.dependencies.add("implementation", libs.findLibrary("kotlin-reflect").get())
        project.dependencies.add("testImplementation", libs.findLibrary("spring-boot-starter-test").get())
        project.dependencies.add("testImplementation", libs.findLibrary("mockk").get())
        project.dependencies.add("testImplementation", libs.findLibrary("kotlin-test").get())

        project.tasks.withType(Test::class.java).configureEach { useJUnitPlatform() }
    }
}
