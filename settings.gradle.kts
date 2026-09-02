rootProject.name = "code-compliance-platform"
include(
    "app-server",
    "module-common",
    "module-auth",
    "module-user",
    "module-project",
    "module-checklist",
    "module-rule",
    "module-scan",
    "module-engine-adapter",
    "module-result",
    "module-report",
    "module-remediation",
    "module-notification",
    "module-openapi",
    "module-admin",
)

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
