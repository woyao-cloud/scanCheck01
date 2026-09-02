package com.example.compliance

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
abstract class AbstractIntegrationTest {
    companion object {
        init {
            // REQUIRED: Docker Desktop 4.7x (engine 29.x, MinAPIVersion >= 1.40) rejects
            // docker-java's hardcoded default API v1.32 with 400 (empty info) — see Ruling #14.
            System.setProperty("api.version", "1.44")
        }

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
            .withDatabaseName("compliance")
            .withUsername("compliance")
            .withPassword("compliance")

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }
}
