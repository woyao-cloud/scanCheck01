plugins { id("compliance-kotlin-module") }

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-scan"))
    implementation(project(":module-result"))
    implementation(project(":module-checklist"))
    // M16 导出：poi/openpdf 都不在 spring-boot-dependencies BOM，版本目录显式 pin
    implementation(libs.poi.ooxml)
    implementation(libs.openpdf)
    // @WebMvcTest 切片把 JSON 反序列化为 Kotlin data class @RequestBody DTO，需要 jackson-module-kotlin
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}
