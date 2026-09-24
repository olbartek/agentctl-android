plugins {
    id("agentctl.jvm.library")
    application
}

dependencies {
    implementation(project(":examples:tinyapp"))
    implementation(project(":agentctl-cli"))
}

application {
    mainClass.set("io.github.olbartek.agentctl.examples.tinyctl.MainKt")
    applicationName = "tinyctl"
    // A JVM process has no argv[0]: this is how the CLI knows to call itself `tinyctl` in its help.
    applicationDefaultJvmArgs = listOf("-Dagentctl.cli.name=tinyctl")
}
