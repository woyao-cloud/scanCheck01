plugins { id("compliance-kotlin-module") }

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-scan"))
    implementation(project(":module-result"))
    implementation(project(":module-checklist"))
}
