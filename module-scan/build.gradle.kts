plugins { id("compliance-kotlin-module") }

dependencies {
    implementation(project(":module-common"))
    implementation(project(":module-project"))
    implementation(project(":module-checklist"))
    implementation(project(":module-rule"))
    implementation(project(":module-result"))
}
