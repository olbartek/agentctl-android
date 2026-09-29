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
// TinyApp's AgentCtl config, apart from the app so a release build leaves agentctl-runtime out.
include(":examples:tinyapp-config")
include(":examples:tinyctl")
// TinyApp as a real Android app, for the bridge end to end (`./tinyctl app launch`, `check --ui`).
include(":examples:tinyapp-android")
// AgentShop, the showcase (examples/agentshop): its logic, its AgentCtl config and tests, and its CLI `shopctl`,
// run through examples/agentshop/appctl. The same app as agentctl-ios's Examples/AgentShop.
include(":examples:agentshop:shop")
include(":examples:agentshop:ctl")
include(":examples:agentshop:shopctl")
// The Compose app: the bridge in debug builds, the UI tests generated from the scenarios (bench/gen_uitests.py).
include(":examples:agentshop:app")
include(":tests")
