plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("agentctl.release.check")
}

// AgentShop as an Android app: the Compose port of the reference's SwiftUI views, on the logic in :shop. Debug builds
// run the store behind the agent bridge (`examples/agentshop/appctl app launch`); release builds build it directly.
android {
    namespace = "io.github.olbartek.agentctl.examples.agentshop.app"
    compileSdk = libs.versions.sdk.compile.get().toInt()
    defaultConfig {
        applicationId = "io.github.olbartek.agentctl.examples.agentshop"
        minSdk = libs.versions.sdk.min.get().toInt()
        targetSdk = libs.versions.sdk.compile.get().toInt()
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    // The generated UI tests (bench/gen_uitests.py) run against the debug app, where the bridge is present but
    // unused: they launch it with `agent-port 0`, so it never clashes with another copy's.
    testBuildType = "debug"
}

dependencies {
    // The logic, on agentctl-core alone: what ships.
    implementation(project(":examples:agentshop:shop"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)

    // The bridge and the config it runs on, which brings agentctl-runtime: debug builds only.
    debugImplementation(project(":agentctl-bridge"))
    debugImplementation(project(":examples:agentshop:ctl"))

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.compose.ui.test.manifest)
}

// A release build must carry none of AgentCtl but agentctl-core, and not AgentShop's config (build-logic's
// `agentctl.release.check`, part of `check`). AgentShop's own classes must be there under their names.
releaseLeavesOutAgentCtl {
    forbidden.add("io/github/olbartek/agentctl/examples/agentshop/ctl/")
    required.add("io/github/olbartek/agentctl/examples/agentshop/app/AgentShop;")
    required.add("io/github/olbartek/agentctl/examples/agentshop/app/MainActivity;")
}
