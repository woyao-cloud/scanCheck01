plugins { id("compliance-kotlin-module") }

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-user"))
    implementation(project(":module-project"))
    implementation(libs.spring.boot.starter.mail)
}
