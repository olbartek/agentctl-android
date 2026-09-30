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
        ): AgentCommand<S, A> = choice(name, listOf("on" to true, "off" to false), help, paths, gate, note, makeAction)

        /**
         * A command whose argument is one of a fixed set of words, each standing for a value, e.g. `filter all` for
         * `null` and `filter shoes` for a shoes category.
         *
         * The argument is documented as `<a|b|c>` and anything else is rejected with `invalid argument: expected a|b|c`,
         * both generated from [options] in their order, so the docs, the error and the accepted words cannot drift
         * apart the way a hand-written [parsing] with its own `argument` string and error text can.
         *
         * @throws IllegalArgumentException when [options] is empty, lists a word twice, or has a word that is empty or
         *   contains `|` or whitespace: the words are the documented `<a|b|c>`, so a malformed list is a host bug.
         */
        public fun <S, A, V> choice(
            name: String,
            options: List<Pair<String, V>>,
            help: String,
            paths: List<String>? = null,
            gate: CommandGate<S>? = null,
            note: String? = null,
            makeAction: (V) -> A,
        ): AgentCommand<S, A> {
            val names = options.map { it.first }
            require(names.isNotEmpty()) { "choice '$name' needs at least one option" }
            require(names.toSet().size == names.size) { "choice '$name' lists an option twice: $names" }
            require(names.all { word -> word.isNotEmpty() && '|' !in word && word.none { it.isWhitespace() } }) {
                "choice '$name' has an option that is empty or contains '|' or whitespace: $names"
            }
            val words = names.joinToString("|")
            return parsing(name, "<$words>", help, paths, gate, note) { text ->
                val option = options.firstOrNull { it.first == text } ?: invalidArgument("expected $words")
                makeAction(option.second)
            }
        }

        /** A command whose argument is one of [options], passed on as typed. See [choice]. */
        @JvmName("choiceOfWords")
        public fun <S, A> choice(
            name: String,
            options: List<String>,
            help: String,
            paths: List<String>? = null,
            gate: CommandGate<S>? = null,
            note: String? = null,
            makeAction: (String) -> A,
        ): AgentCommand<S, A> = choice(name, options.map { it to it }, help, paths, gate, note, makeAction)

        /**
         * A command whose argument is one of [of], spelled by [word], offered in their order, e.g.
         * `choice("tab", of = Tab.entries, word = Tab::code, help = "Switch tab.") { TabSelected(it) }` documents
         * `tab <shop|cart>`. See [choice].
         */
        public fun <S, A, V> choice(
            name: String,
            of: Iterable<V>,
            word: (V) -> String,
            help: String,
            paths: List<String>? = null,
            gate: CommandGate<S>? = null,
            note: String? = null,
            makeAction: (V) -> A,
        ): AgentCommand<S, A> = choice(name, of.map { word(it) to it }, help, paths, gate, note, makeAction)

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

    public companion object {
        /**
         * `back` for when no container has anything to pop: it fails with `nothing to go back to on <path>` instead
         * of the runner's "unknown command", which would read as if the app had no `back` at all.
         *
         * The root appends it last ([appendingBackFallback]), so any container that can pop shadows it, and lists
         * [CommandDoc.backFallback] on every screen of its registry.
         */
        public fun <A> backFallback(path: String, source: String): ResolvedCommand<A> =
            ResolvedCommand("back", null, CommandDoc.BACK_FALLBACK_HELP, source, null) {
                notApplicable("nothing to go back to on $path")
            }
    }
}
