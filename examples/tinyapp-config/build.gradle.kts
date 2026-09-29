plugins {
    id("agentctl.jvm.library")
}

// TinyApp's AgentCtl config: the module a host keeps apart from its app, because it needs agentctl-runtime. Only the
// debug app (debugImplementation), the CLI and the tests depend on it, so a release build carries no AgentCtl runtime.
dependencies {
    api(project(":examples:tinyapp"))
    api(project(":agentctl-runtime"))
}
