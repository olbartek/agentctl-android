plugins {
    alias(libs.plugins.android.application)
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
    // TinyApp's screens and store. (Its module also holds the AgentCtl config, so it brings agentctl-runtime into
    // a release build too; a real app keeps its config in a module only the debug build and the CLI depend on.)
    implementation(project(":examples:tinyapp"))
    implementation(libs.kotlinx.coroutines.android)
    // The bridge: debug builds only.
    debugImplementation(project(":agentctl-bridge"))
}
