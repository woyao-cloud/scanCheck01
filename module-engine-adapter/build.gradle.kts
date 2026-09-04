plugins { id("compliance-kotlin-module") }

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-result"))
    implementation("org.springframework:spring-web")   // M15 (R-M15-D7)：RestClient；BOM 3.3.5 管版本
}
