plugins {
    alias(libs.plugins.android.application)
    id("agentctl.release.check")
}

android {
    namespace = "io.github.olbartek.agentctl.examples.tinyapp.android"
    compileSdk = libs.versions.sdk.compile.get().toInt()
    defaultConfig {
        applicationId = "io.github.olbartek.agentctl.examples.tinyapp"
        minSdk = libs.versions.sdk.min.get().toInt()
        targetSdk = libs.versions.sdk.compile.get().toInt()
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // TinyApp's screens and store, on agentctl-core alone: what ships.
    implementation(project(":examples:tinyapp"))
    implementation(libs.kotlinx.coroutines.android)
    // The bridge and the config it runs on, which brings agentctl-runtime: debug builds only.
    debugImplementation(project(":agentctl-bridge"))
    debugImplementation(project(":examples:tinyapp-config"))
}

// A release build must carry none of AgentCtl but agentctl-core, and not TinyApp's config (build-logic's
// `agentctl.release.check`, part of `check`). TinyApp's own classes must be there under their names.
releaseLeavesOutAgentCtl {
    forbidden.add("io/github/olbartek/agentctl/examples/tinyapp/TinyAppConfig")
    required.add("io/github/olbartek/agentctl/examples/tinyapp/TinyApp;")
}
