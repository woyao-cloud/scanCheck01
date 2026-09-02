package com.example.compliance

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {
    companion object {
        init {
            // REQUIRED: Docker Desktop 4.7x (engine 29.x, MinAPIVersion >= 1.40) rejects
            // docker-java's hardcoded default API v1.32 with 400 (empty info) — see Ruling #14.
            System.setProperty("api.version", "1.44")
        }

        // One PostgreSQL container per test JVM, started once from the companion object.
        // NOTE (Task 1.1 deviation, root cause = latent M0 harness defect): Testcontainers 1.20.4
        // stops a static @Container at the END of each test class and starts a fresh one (new random
        // host port) for the next class. Spring's test context is CACHED across test classes and its
        // @DynamicPropertySource datasource URL still points at the old, now-dead port, so the second
        // integration test class in a JVM fails with "Connection refused" / Hikari timeout. Starting
        // the container here (once per JVM) keeps the port stable for the whole JVM; Ryuk cleans up
        // the container at JVM exit. Verified: both SmokeIntegrationTest and UserRepositoryIntegrationTest
        // pass together in one `:app-server:test` run.
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
            .withDatabaseName("compliance")
            .withUsername("compliance")
            .withPassword("compliance")
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }
}
