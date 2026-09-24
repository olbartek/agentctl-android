package io.github.olbartek.agentctl

/** The runtime commands available on every screen, independent of any specific app. */
public object AgentRegistry {
    /**
     * The `mock` example for a host that supplies no [DocsText.mockExample] of its own: a placeholder, because
     * only the host knows what its clients are called.
     */
    public const val DEFAULT_MOCK_EXAMPLE: String = "mock <client.method> <error>"

    /** The names the runner handles itself, in the order the "Valid here" list appends them (CONTRACT.md §1.3). */
    public val runtimeCommandNames: List<String> = listOf("expect", "advance", "mock")

    /**
     * @param mockExample the example in `mock`'s help, e.g. `mock orders.fetchOrders network`. The client and
     *   method names are the host's, so it comes from [DocsText.mockExample].
     */
    public fun runtimeCommands(mockExample: String = DEFAULT_MOCK_EXAMPLE): List<CommandDoc> = listOf(
        CommandDoc(
            name = "expect",
            argument = "k=v [k=v …]",
            help = "Assert on screen, any summary key, call=<client.method> (called during the previous step), " +
                "error=<code|none> or pending=<n>. A failed assertion fails the script.",
            source = "runtime",
        ),
        CommandDoc(
            name = "advance",
            argument = "<duration>",
            help = "Advance the test clock, e.g. 500ms, 30s, 5m, 1h. Headless only.",
            source = "runtime",
        ),
        CommandDoc(
            name = "mock",
            argument = "<client.method> <error>",
            help = "Make the next call to that method fail, e.g. $mockExample.",
            source = "runtime",
        ),
    )

    /** Every command name across `screens`, including the runtime commands. */
    public fun allCommandNames(screens: List<ScreenDoc>): Set<String> =
        (screens.flatMap { screen -> screen.commands.map { it.name } } + runtimeCommands().map { it.name }).toSet()
}

/**
 * Plain-text rendering for `<cli> screens`: the same command reference the generated document holds, so it takes
 * the same host-supplied [DocsText.mockExample].
 */
public object ScreensRenderer {
    public fun render(screens: List<ScreenDoc>, mockExample: String = AgentRegistry.DEFAULT_MOCK_EXAMPLE): String {
        val lines = mutableListOf<String>()
        for (screen in screens) {
            lines.add("${screen.path}  [${screen.screen}]")
            if (screen.commands.isEmpty()) lines.add("  (no screen commands)")
            val width = maxOf(24, (screen.commands.maxOfOrNull { Graphemes.count(it.usage) } ?: 0) + 2)
            for (command in screen.commands) {
                val source = if (command.source == screen.screen) "" else "  (${command.source})"
                val note = command.note?.let { " [$it]" } ?: ""
                lines.add("  " + command.usage.padGraphemes(width) + command.help + note + source)
            }
            if (screen.summaryKeys.isNotEmpty()) lines.add("  summary: " + screen.summaryKeys.joinToString(" "))
        }
        lines.add("every screen  [runtime]")
        val runtimeCommands = AgentRegistry.runtimeCommands(mockExample)
        val width = (runtimeCommands.maxOfOrNull { Graphemes.count(it.usage) } ?: 0) + 2
        for (command in runtimeCommands) {
            lines.add("  " + command.usage.padGraphemes(width) + command.help)
        }
        return lines.joinToString("\n")
    }
}

/** Defaults of the in-app bridge that both of its ends rely on: the app and the CLI (CONTRACT.md §8). */
public object BridgeDefaults {
    /** The port the bridge listens on without `agent-port`, and the port the CLI connects to without `--port`. */
    public const val PORT: Int = 8765
}

/**
 * How this process names the CLI it is running. A JVM process has no `argv[0]`, so the host's launcher passes
 * its name as the `agentctl.cli.name` system property (the `application` plugin's `applicationDefaultJvmArgs`);
 * without one it is `appctl`. The default for [DocsText.invocation] and the CLI's help examples.
 */
public object CLIName {
    public val current: String get() = System.getProperty("agentctl.cli.name")?.takeIf { it.isNotBlank() } ?: "appctl"
}
