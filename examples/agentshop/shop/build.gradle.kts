plugins {
    id("agentctl.jvm.library")
}

// AgentShop's logic: models, the mocked clients, every screen's state, reducer and agent surface, and the root.
// Only agentctl-core: this module ships in the release app, so none of AgentCtl's runtime may reach it.
dependencies {
    api(project(":agentctl-core"))

    // The tests drive the reducers on the runtime's virtual-time dispatcher, as the headless host does.
    testImplementation(project(":agentctl-runtime"))
}
