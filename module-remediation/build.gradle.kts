plugins { id("compliance-kotlin-module") }

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-result"))
    implementation(project(":module-scan"))
    // @WebMvcTest 切片把 JSON 反序列化为 Kotlin data class @RequestBody DTO（controller），
    // 需要 jackson-module-kotlin；app-server 仅 runtimeOnly 提供，切片测试类路径须显式加入。
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}
