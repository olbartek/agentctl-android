package io.github.olbartek.agentctl.bridge

import io.github.olbartek.agentctl.runtime.AgentLaunchOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What this guards: the token a test runner that launches the app itself passes as the `agent-token` extra: 32
 * lowercase hex characters, the form the app accepts, and a new one each time.
 */
class AgentLaunchArgumentsTest {
    @Test
    fun makeTokenMakesFreshWellFormedTokens() {
        val tokens = (1..1000).map { AgentLaunchArguments.makeToken() }
        assertTrue(tokens.all { Regex("[0-9a-f]{32}").matches(it) }, tokens.take(3).toString())
        assertTrue(tokens.all(AgentLaunchOptions::isToken))
        assertEquals(tokens.size, tokens.toSet().size)
        assertEquals("agent-token", AgentLaunchArguments.TOKEN)
    }
}
