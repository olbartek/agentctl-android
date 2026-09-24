plugins {
    id("agentctl.jvm.library")
}

dependencies {
    api(project(":agentctl-core"))
    // TinyAppConfig is an AppCtlConfig and builds the two hosts.
    api(project(":agentctl-runtime"))
}
