plugins { id("compliance-kotlin-module") }

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-project"))
    implementation(project(":module-scan"))
    implementation(project(":module-result"))
}
