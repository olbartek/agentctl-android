package io.github.olbartek.agentctl

/** One `key=value` pair in a step summary. */
public data class SummaryItem(val key: String, val value: String) {
    public constructor(key: String, value: Boolean) : this(key, if (value) "true" else "false")
    public constructor(key: String, value: Int) : this(key, value.toString())
    public constructor(key: String, value: Long) : this(key, value.toString())
}

/**
 * One screen of an app, described for agents: its path, its summary, its error code and its commands.
 * Declared beside the screen's state and reducer, usually as an `object` in `<Screen>Agent.kt`;
 * `examples/tinyapp` has two of them.
 */
public interface AgentScreen<S, A> {
    /** Shown as the command source in `<cli> screens`. Defaults to the class name without an `Agent` suffix. */
    public val screenName: String get() = javaClass.simpleName.removeSuffix("Agent")

    /** Every path this screen can report, for the docs (`<id>` stands for a variable part). */
    public val screenPaths: List<String>

    public fun screenPath(state: S): String

    /** Every key [summary] can emit, for the docs and `expect` error messages. */
    public val summaryKeys: List<String>

    /** Ordered, compact key/value pairs. Never include secrets such as passwords. */
    public fun summary(state: S): List<SummaryItem>

    /** The current error code (`error=<code>`), if any. */
    public fun errorCode(state: S): String? = null

    /** Sent by the headless runtime when the screen appears, standing in for the view's own appearance. */
    public val onAppear: A? get() = null

    public val commands: List<AgentCommand<S, A>>

    public fun activeScreen(state: S): ActiveScreen<A> {
        val path = screenPath(state)
        return ActiveScreen(
            path = path,
            identity = path,
            summary = summary(state),
            errorCode = errorCode(state),
            appearAction = onAppear,
            commands = commands.filter { it.paths?.contains(path) ?: true }.map { it.resolve(state, screenName) },
        )
    }

    public val screenDocs: List<ScreenDoc>
        get() = screenPaths.map { path ->
            ScreenDoc(
                path = path,
                screen = screenName,
                commands = commands.filter { it.paths?.contains(path) ?: true }.map { it.doc(screenName) },
                summaryKeys = summaryKeys,
            )
        }
}

/**
 * A stack or tab container — or the app's root — that resolves its active child, lifts the child's actions,
 * and appends its own commands (`back`, `tab`, …).
 */
public interface AgentContainer<S, A> {
    public fun activeScreen(state: S): ActiveScreen<A>

    /** Every screen reachable through this container, with inherited commands appended. */
    public val registry: List<ScreenDoc>

    /** Shown as the source of [inheritedCommands]. Defaults to the class name without an `Agent` suffix. */
    public val containerName: String get() = javaClass.simpleName.removeSuffix("Agent")

    /**
     * Commands this container offers on every screen beneath it, whatever is pushed: a root's `login-as` and `reset`,
     * a tab bar's `tab`. Declared once, they reach both the active screen ([inheritingCommands] with a state) and every
     * screen of the registry ([inheritingCommands] with docs), so the two cannot disagree. A command with `paths` is
     * offered only on those descendant paths. Commands that depend on what is pushed, such as `back`, stay in
     * [activeScreen]. Defaults to none.
     */
    public val inheritedCommands: List<AgentCommand<S, A>> get() = emptyList()

    /**
     * [screen] with [inheritedCommands] added, resolved against this container's [state]. Call it on the child's
     * screen in [activeScreen].
     *
     * They go after the commands of the screen and of the containers beneath this one, and before any command this
     * container added itself (its `back`), whichever of the two is applied first; a command whose name is already
     * there keeps the descendant's version, as with [appending].
     */
    public fun inheritingCommands(screen: ActiveScreen<A>, state: S): ActiveScreen<A> {
        val extra = inheritedCommands.filter { it.paths?.contains(screen.path) ?: true }.map { it.resolve(state, containerName) }
        return screen.copy(commands = insertingInherited(extra, screen.commands, { it.name }, { it.source }))
    }

    /**
     * [docs] with [inheritedCommands] added to each screen, in the same place as on the active screen. Call it on every
     * screen the container's [registry] lists.
     */
    public fun inheritingCommands(docs: List<ScreenDoc>): List<ScreenDoc> = docs.map { screen ->
        val extra = inheritedCommands.filter { it.paths?.contains(screen.path) ?: true }.map { it.doc(containerName) }
        screen.copy(commands = insertingInherited(extra, screen.commands, { it.name }, { it.source }))
    }

    private fun <C> insertingInherited(extra: List<C>, commands: List<C>, name: (C) -> String, source: (C) -> String): List<C> {
        val existing = commands.map(name).toSet()
        val own = commands.indexOfFirst { source(it) == containerName }.takeIf { it >= 0 } ?: commands.size
        return commands.subList(0, own) + extra.filter { name(it) !in existing } + commands.subList(own, commands.size)
    }
}

/** The screen an agent is looking at, with commands lifted to some ancestor's action type. */
public data class ActiveScreen<out A>(
    val path: String,
    /** Changes whenever a different screen instance becomes active (path plus stack element ids). */
    val identity: String,
    val summary: List<SummaryItem>,
    val errorCode: String?,
    val appearAction: A?,
    /** Leaf commands first, then ancestors'. */
    val commands: List<ResolvedCommand<A>>,
) {
    public fun <P> map(embed: (A) -> P): ActiveScreen<P> = ActiveScreen(
        path = path,
        identity = identity,
        summary = summary,
        errorCode = errorCode,
        appearAction = appearAction?.let(embed),
        commands = commands.map { it.map(embed) },
    )

    /** Prefixes the identity, e.g. with a stack element id, so re-pushing the same path counts as a new appearance. */
    public fun identified(component: String): ActiveScreen<A> = copy(identity = "$component/$identity")

    public fun command(name: String): ResolvedCommand<A>? = commands.firstOrNull { it.name == name }
}

/** Appends ancestor commands. Commands that a descendant already provides keep the descendant's version. */
public fun <A> ActiveScreen<A>.appending(extra: List<ResolvedCommand<A>>): ActiveScreen<A> {
    val existing = commands.map { it.name }.toSet()
    return copy(commands = commands + extra.filter { it.name !in existing })
}

/**
 * Appends [ResolvedCommand.backFallback] for this screen's path. The root calls it last, so a `back` from any container
 * beneath it wins.
 */
public fun <A> ActiveScreen<A>.appendingBackFallback(source: String): ActiveScreen<A> =
    appending(listOf(ResolvedCommand.backFallback(path, source)))

/** A command as documented by the CLI's `screens` command and the generated command reference. */
public data class CommandDoc(
    val name: String,
    val argument: String?,
    val help: String,
    val source: String,
    val note: String? = null,
) {
    val usage: String get() = if (argument != null) "$name $argument" else name

    public companion object {
        /** The help of the root's `back` fallback, in the docs and on the resolved command alike. */
        public const val BACK_FALLBACK_HELP: String = "Fails with 'nothing to go back to' when no screen is pushed."

        /** How the root's [ResolvedCommand.backFallback] is documented, on every screen of its registry. */
        public fun backFallback(source: String): CommandDoc = CommandDoc("back", null, BACK_FALLBACK_HELP, source)
    }
}

/** A screen path with every command available there, including inherited ones. */
public data class ScreenDoc(
    val path: String,
    val screen: String,
    val commands: List<CommandDoc>,
    val summaryKeys: List<String>,
) {
    public fun inheriting(extra: List<CommandDoc>): ScreenDoc {
        val existing = commands.map { it.name }.toSet()
        return copy(commands = commands + extra.filter { it.name !in existing })
    }
}
