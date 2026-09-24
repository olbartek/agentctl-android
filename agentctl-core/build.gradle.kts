plugins {
    id("agentctl.jvm.library")
    id("agentctl.published")
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
