plugins { id("compliance-kotlin-module") }

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-scan"))
    implementation(project(":module-result"))
    implementation(project(":module-checklist"))
    // @WebMvcTest 切片把 JSON 反序列化为 Kotlin data class @RequestBody DTO，需要 jackson-module-kotlin
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}
