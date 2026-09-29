plugins {
    id("agentctl.jvm.library")
    application
}

dependencies {
    implementation(project(":examples:agentshop:ctl"))
    implementation(project(":agentctl-cli"))
}

application {
    mainClass.set("io.github.olbartek.agentctl.examples.agentshop.shopctl.MainKt")
    applicationName = "shopctl"
    // A JVM process has no argv[0]: this is how the CLI knows to call itself `shopctl` in its help.
    applicationDefaultJvmArgs = listOf("-Dagentctl.cli.name=shopctl")
}
