plugins {
    id("compliance-kotlin-module")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-auth"))
    implementation(project(":module-user"))
    implementation(project(":module-project"))
    implementation(project(":module-checklist"))
    implementation(project(":module-rule"))
    implementation(project(":module-scan"))
    implementation(project(":module-engine-adapter"))
    implementation(project(":module-result"))
    implementation(project(":module-report"))
    implementation(project(":module-remediation"))
    implementation(project(":module-notification"))
    implementation(project(":module-openapi"))
    implementation(project(":module-admin"))
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    // Jackson cannot deserialize Kotlin data classes (@RequestBody DTOs) without jackson-module-kotlin;
    // spring-boot-starter-json does not include it. Assembled here (app-server is the only deployable)
    // because module-common is frozen for the M-series. Version managed by the spring-boot-dependencies BOM.
    runtimeOnly("com.fasterxml.jackson.module:jackson-module-kotlin")
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.spring.security.test)
}
