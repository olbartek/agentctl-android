plugins {
    id("agentctl.jvm.library")
}

// TinyApp itself: its screens, their agent surfaces and its store, on agentctl-core alone, so it can ship in a release
// build. Its AgentCtl config, which needs agentctl-runtime, is examples/tinyapp-config.
dependencies {
    api(project(":agentctl-core"))
}
