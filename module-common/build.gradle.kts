plugins { id("compliance-kotlin-module") }

dependencies {
    api(libs.spring.boot.starter.web)
    api(libs.spring.boot.starter.security)
    api(libs.spring.boot.starter.data.jpa)
    api(libs.spring.boot.starter.validation)
    api(libs.springdoc.openapi.starter.webmvc.ui)
}
