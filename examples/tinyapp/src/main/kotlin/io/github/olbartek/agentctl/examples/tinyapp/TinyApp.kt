package io.github.olbartek.agentctl.examples.tinyapp

import io.github.olbartek.agentctl.AgentEnvironment
import io.github.olbartek.agentctl.Store

/**
 * TinyApp's entry point for whoever runs it: its store, built from an [AgentEnvironment].
 *
 * This module is what ships: the screens, their agent surfaces and this, on `agentctl-core` alone. Everything that
 * needs AgentCtl's runtime — the config, the headless and live hosts — lives in `examples/tinyapp-config`, which
 * only the debug app, the CLI and the tests depend on.
 */
object TinyApp {
    /** TinyApp's store on any environment: the headless host's, the live host's, or a release build's own. */
    fun store(environment: AgentEnvironment): Store<TinyRoot.State, TinyRoot.Action> = Store(
        initialState = TinyRoot.State(),
        reducer = TinyRoot.reducer(ItemsClient.mock(environment.mocks), environment.clock),
        scope = environment.scope,
    )
}
