plugins {
    id("agentctl.jvm.library")
}

// AgentShop's AgentCtl integration: the AppCtlConfig and its headless and live hosts. The debug app and the CLI
// depend on it; the release app does not.
dependencies {
    api(project(":examples:agentshop:shop"))
    api(project(":agentctl-runtime"))

    testImplementation(project(":agentctl-cli"))
    testImplementation(project(":agentctl-test-support"))
}

tasks.test {
    // The scenarios and the committed command reference are read from examples/agentshop, the example's root.
    val exampleRoot = projectDir.parentFile
    systemProperty("agentshop.root", exampleRoot.absolutePath)
    inputs.dir(File(exampleRoot, "scenarios"))
    inputs.file(File(exampleRoot, "agent-commands.md"))
}
