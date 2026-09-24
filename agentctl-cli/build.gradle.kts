plugins {
    id("agentctl.jvm.library")
    id("agentctl.published")
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":agentctl-runtime"))
    implementation(libs.clikt.core)
}
