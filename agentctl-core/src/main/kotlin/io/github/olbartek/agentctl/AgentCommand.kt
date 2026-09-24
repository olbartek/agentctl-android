package io.github.olbartek.agentctl

/** Why an agent command could not be turned into an action. */
public sealed class AgentCommandError {
    public abstract val message: String

    /** The command needs an argument, described by [expected] (e.g. `"<text>"`). */
    public data class MissingArgument(val expected: String) : AgentCommandError() {
        override val message: String get() = "missing argument: expected $expected"
    }

    /** The command takes no argument but got one. */
    public data class UnexpectedArgument(val argument: String) : AgentCommandError() {
        override val message: String get() = "unexpected argument '$argument': this command takes none"
    }

    /** The argument was given but is not valid. */
    public data class InvalidArgument(val reason: String) : AgentCommandError() {
        override val message: String get() = "invalid argument: $reason"
    }

    /** The command exists but does not apply in the current state (e.g. `back` with nothing to pop). */
    public data class NotApplicable(val reason: String) : AgentCommandError() {
        override val message: String get() = reason
    }
}

/** Thrown by a command's `makeAction` to refuse its argument. See [AgentCommandError]. */
public class AgentCommandException(public val error: AgentCommandError) : Exception(error.message)

/** Refuses the argument: `invalid argument: <reason>`. */
public fun invalidArgument(reason: String): Nothing = throw AgentCommandException(AgentCommandError.InvalidArgument(reason))

/** Refuses the command in the current state: the reason, as it is. */
public fun notApplicable(reason: String): Nothing = throw AgentCommandException(AgentCommandError.NotApplicable(reason))

/** Disables a command in some states, mirroring a disabled button. */
public class CommandGate<in S>(
    /** Shown when the command is disabled, e.g. `"canSubmit=false"`. */
    public val hint: String,
    public val isEnabled: (S) -> Boolean,
)

/** One command an agent can send on a screen. Declarations live beside the screen, in `<Screen>Agent.kt`. */
public class AgentCommand<S, A>(
    public val name: String,
    /** Describes the argument, e.g. `"<text>"` or `"<on|off>"`; `null` if the command takes none. */
    public val argument: String?,
    /** One line, shown by `<cli> screens` and in the generated docs. */
    public val help: String,
    /** The screen paths this command is offered on; `null` means every path of the screen. */
    public val paths: List<String>? = null,
    /** Disables the command in some states; `null` means always enabled. */
    public val gate: CommandGate<S>? = null,
    /** A note for the docs, e.g. `"when the stack is not empty"`. */
    public val note: String? = null,
    /** Turns the argument into an action, or throws [AgentCommandException]. */
    public val makeAction: (String?) -> A,
) {
    public fun doc(source: String): CommandDoc =
        CommandDoc(name, argument, help, source, note ?: gate?.let { "disabled when ${it.hint}" })

    public fun resolve(state: S, source: String): ResolvedCommand<A> {
        val gate = gate
        val disabledReason = if (gate != null && !gate.isEnabled(state)) gate.hint else null
        return ResolvedCommand(name, argument, help, source, disabledReason, makeAction)
    }

    public companion object {
        /** A command without an argument that always sends the same action. */
        public fun <S, A> action(
            name: String,
            help: String,
            action: A,
            paths: List<String>? = null,
            gate: CommandGate<S>? = null,
            note: String? = null,
        ): AgentCommand<S, A> = AgentCommand(name, null, help, paths, gate, note) { argument ->
            if (argument != null) throw AgentCommandException(AgentCommandError.UnexpectedArgument(argument))
            action
        }

        /** A command whose argument is free text: the rest of the line, unquoted. */
        public fun <S, A> text(
            name: String,
            help: String,
            argument: String = "<text>",
            paths: List<String>? = null,
            gate: CommandGate<S>? = null,
            note: String? = null,
            makeAction: (String) -> A,
        ): AgentCommand<S, A> = AgentCommand(name, argument, help, paths, gate, note) { text ->
            if (text == null) throw AgentCommandException(AgentCommandError.MissingArgument(argument))
            makeAction(text)
        }

        /** A switch: the argument is `on` or `off`, e.g. `terms on`. */
        public fun <S, A> onOff(
            name: String,
            help: String,
            paths: List<String>? = null,
            gate: CommandGate<S>? = null,
            note: String? = null,
            makeAction: (Boolean) -> A,
        ): AgentCommand<S, A> = parsing(name, "<on|off>", help, paths, gate, note) { text ->
            when (text) {
                "on" -> makeAction(true)
                "off" -> makeAction(false)
                else -> invalidArgument("expected on|off")
            }
        }

        /** A command whose argument must be parsed and may be rejected (throw with [invalidArgument]). */
        public fun <S, A> parsing(
            name: String,
            argument: String,
            help: String,
            paths: List<String>? = null,
            gate: CommandGate<S>? = null,
            note: String? = null,
            makeAction: (String) -> A,
        ): AgentCommand<S, A> = AgentCommand(name, argument, help, paths, gate, note) { text ->
            if (text == null) throw AgentCommandException(AgentCommandError.MissingArgument(argument))
            makeAction(text)
        }
    }
}

/** A command with its enabled state already evaluated and its action lifted to some ancestor's action type. */
public class ResolvedCommand<out A>(
    public val name: String,
    public val argument: String?,
    public val help: String,
    /** The screen or container that contributed the command. */
    public val source: String,
    /** Non-null when the command is disabled in the current state. */
    public val disabledReason: String?,
    public val makeAction: (String?) -> A,
) {
    public val usage: String get() = if (argument != null) "$name $argument" else name

    public fun <P> map(embed: (A) -> P): ResolvedCommand<P> =
        ResolvedCommand(name, argument, help, source, disabledReason) { embed(makeAction(it)) }
}
