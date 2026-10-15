plugins {
    id("agentctl.jvm.library")
    application
}

dependencies {
    implementation(project(":examples:tinymvvm"))
    implementation(project(":agentctl-cli"))
}

application {
    mainClass.set("io.github.olbartek.agentctl.examples.tinymvvmctl.MainKt")
    applicationName = "tinymvvmctl"
    // A JVM process has no argv[0]: this is how the CLI knows to call itself `tinymvvmctl` in its help.
    applicationDefaultJvmArgs = listOf("-Dagentctl.cli.name=tinymvvmctl")
}
