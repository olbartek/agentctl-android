plugins {
    id("agentctl.jvm.library")
}

// The package's own suite, driven against the example app (the counterpart of the reference's AgentCtlTests).
dependencies {
    testImplementation(project(":agentctl-core"))
    testImplementation(project(":agentctl-runtime"))
    testImplementation(project(":agentctl-cli"))
    testImplementation(project(":agentctl-test-support"))
    testImplementation(project(":examples:tinyapp"))
}

tasks.test {
    // CONTRACT.md, the scenarios and the committed docs are read from the repository root.
    systemProperty("agentctl.root", rootDir.absolutePath)
    inputs.file(rootProject.file("CONTRACT.md"))
    inputs.dir(rootProject.file("examples/tinyapp/scenarios"))
    inputs.file(rootProject.file("examples/tinyapp/agent-commands.md"))
}
