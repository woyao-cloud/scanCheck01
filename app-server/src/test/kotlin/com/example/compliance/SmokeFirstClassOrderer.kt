package com.example.compliance

import org.junit.jupiter.api.ClassOrderer
import org.junit.jupiter.api.ClassOrdererContext

/**
 * Deterministic test-class ordering for the shared PostgreSQL container (Ruling #51).
 *
 * The frozen [SmokeIntegrationTest] asserts an absolute `audit_log` count after writing one
 * audit — valid only if it runs before any other audit-writing class in this JVM. JUnit 5's
 * default discovery order is directory-dependent on NTFS and shifts as test classes are added,
 * so pin Smoke first explicitly; the remaining classes run in display-name order for
 * reproducibility across runs and machines.
 */
class SmokeFirstClassOrderer : ClassOrderer {

    override fun orderClasses(context: ClassOrdererContext) {
        context.getClassDescriptors().sortWith { a, b ->
            val aSmoke = a.testClass == SmokeIntegrationTest::class.java
            val bSmoke = b.testClass == SmokeIntegrationTest::class.java
            when {
                aSmoke && !bSmoke -> -1
                !aSmoke && bSmoke -> 1
                else -> a.displayName.compareTo(b.displayName)
            }
        }
    }
}
