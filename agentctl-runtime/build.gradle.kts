plugins {
    id("agentctl.jvm.library")
    id("agentctl.published")
}

kotlin {
    explicitApi()
    compilerOptions {
        // VirtualTimeDispatcher implements kotlinx.coroutines' Delay, as kotlinx-coroutines-test does.
        optIn.add("kotlinx.coroutines.InternalCoroutinesApi")
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

dependencies {
    api(project(":agentctl-core"))
}
