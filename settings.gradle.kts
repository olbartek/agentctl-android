pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "agentctl-android"

// The libraries a host depends on.
include(":agentctl-core")
include(":agentctl-runtime")
include(":agentctl-cli")
include(":agentctl-test-support")
include(":agentctl-bridge")

// The example app and its CLI: the fixture the suite in :tests drives. Not published.
include(":examples:tinyapp")
include(":examples:tinyctl")
// TinyApp as a real Android app, for the bridge end to end (`./tinyctl app launch`, `check --ui`).
include(":examples:tinyapp-android")
include(":tests")
