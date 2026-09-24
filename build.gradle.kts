plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}

allprojects {
    group = "io.github.olbartek.agentctl"
    version = providers.gradleProperty("agentctlVersion").get()
}
