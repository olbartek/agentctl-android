package io.github.olbartek.agentctl.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.github.olbartek.agentctl.BridgeDefaults
import io.github.olbartek.agentctl.CLIName
import io.github.olbartek.agentctl.runtime.AppCtlConfig
import io.github.olbartek.agentctl.runtime.RunStatus
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * The CLI as a library: a host's executable hands it an [AppCtlConfig] and AgentCtl runs the same commands against
 * that app.
 *
 * ```kotlin
 * fun main(args: Array<String>) = AgentCtl.main(MyAppConfig.appCtl, args)
 * ```
 */
public object AgentCtl {
    /** Runs the CLI and exits the process with the command's exit code (CONTRACT.md §5). */
    public fun <S, A> main(config: AppCtlConfig<S, A>, args: Array<String>): Nothing =
        exitProcess(run(config, args.toList()))

    /**
     * Runs the CLI and returns its exit code: 0 on success, the command's own code on failure, 2 for a malformed
     * command line (CONTRACT.md §5). Help goes to [out] with 0; every error, usage errors included, to [err].
     */
    public fun <S, A> run(
        config: AppCtlConfig<S, A>,
        args: List<String>,
        out: PrintStream = System.out,
        err: PrintStream = System.err,
        environment: Map<String, String> = System.getenv(),
        workingDirectory: File = File(System.getProperty("user.dir")),
    ): Int {
        val io = Io(out, err)
        val cli = Cli(config, io, environment, workingDirectory)
        val root = command(cli)
        return try {
            root.parse(args)
            0
        } catch (error: CliktError) {
            val status = exitStatus(error)
            val message = root.getFormattedHelp(error)
            if (!message.isNullOrEmpty()) {
                if (status == 0 && !error.printError) out.println(message) else err.println(message)
            }
            status
        }
    }

    /**
     * The exit code for an error thrown while parsing or running a command: 2 for every usage error (Clikt's own is
     * 1), 0 for `--help`, and the code a subcommand returned for its own failures.
     */
    internal fun exitStatus(error: CliktError): Int = when (error) {
        is ProgramResult -> error.statusCode
        is PrintHelpMessage -> if (error.error) RunStatus.USAGE.code else 0
        is PrintMessage -> error.statusCode
        is UsageError -> RunStatus.USAGE.code
        else -> if (error.statusCode == 1) RunStatus.USAGE.code else error.statusCode
    }

    /** The command tree, for rendering help pages in tests. */
    internal fun <S, A> command(cli: Cli<S, A>): CoreCliktCommand = Root(cli)
        .subcommands(
            RunCommand(cli),
            StateCommand(cli),
            ScreensCommand(cli),
            DocsCommand(cli),
            TestCommand(cli),
            SnapshotsCommand(cli),
            CheckCommand(cli),
            AppGroup().subcommands(AppLaunchCommand(cli), AppRunCommand(cli), AppGetCommand(cli, "state", "/state"), AppGetCommand(cli, "screens", "/screens")),
        )

    /** Example lines. The plain-text help formatter prints an epilog as it is, line breaks included. */
    internal fun examples(vararg lines: String): String = "Examples:\n" + lines.joinToString("\n") { "  $it" }

    /**
     * The name the usage lines give the CLI: the launcher's (`agentctl.cli.name`), or else the last word of the
     * host's invocation, so `./tinyctl` calls itself `tinyctl` even where no launcher set the property.
     */
    internal fun commandName(config: AppCtlConfig<*, *>): String =
        System.getProperty("agentctl.cli.name")?.takeIf { it.isNotBlank() }
            ?: config.help.invocation.trim().split(Regex("\\s+")).last().substringAfterLast('/').ifEmpty { CLIName.current }

    private class Root<S, A>(private val cli: Cli<S, A>) : CoreCliktCommand(name = commandName(cli.config)) {
        override fun help(context: Context): String =
            "Drive ${cli.config.name} headlessly, run its scenarios and check the verification ladder."

        override fun helpEpilog(context: Context): String = listOfNotNull(
            cli.config.help.note,
            "Command reference: ${cli.config.docsPath} (or ${cli.config.help.invocation} screens).",
        ).joinToString("\n\n")

        override fun run() = Unit
    }

    private class AppGroup : CoreCliktCommand(name = "app") {
        override fun help(context: Context): String = "Launch the app on a device and drive it through its agent bridge."

        override fun run() = Unit
    }

    /** A subcommand: its one-line abstract, its discussion (examples), and a body returning its exit code. */
    private abstract class Subcommand(
        name: String,
        private val abstract: String,
        private val discussion: String? = null,
    ) : CoreCliktCommand(name = name) {
        override fun help(context: Context): String = abstract

        override fun helpEpilog(context: Context): String = discussion ?: ""

        abstract fun execute(): Int

        override fun run() {
            val code = execute()
            if (code != 0) throw ProgramResult(code)
        }
    }

    private class RunCommand<S, A>(private val cli: Cli<S, A>) : Subcommand(
        "run",
        "Run a script against a fresh headless app and print one step per command.",
        cli.config.help.let { help ->
            examples(
                "${help.invocation} run \"${help.runScript(0)}\"",
                "${help.invocation} run --session ${help.sessionPath} \"${help.runScript(1)}\"",
                "${help.invocation} run --json \"${help.runScript(2)}\"",
            )
        },
    ) {
        private val script by argument(help = "Commands separated by ';' or newlines. '#' starts a comment.")
        private val session by option(help = "Replay the commands saved in this file first, then append the new ones that succeed.")
        private val diff by option(help = "Print a state diff after each step.").flag()
        private val json by option(help = "Print a JSON array of steps instead of text.").flag()

        override fun execute(): Int = cli.run(script, session, diff, json)
    }

    private class StateCommand<S, A>(private val cli: Cli<S, A>) : Subcommand(
        "state",
        "Print the full root state after replaying an optional session file.",
        examples("${cli.config.help.invocation} state", "${cli.config.help.invocation} state --session ${cli.config.help.sessionPath}"),
    ) {
        private val session by option(help = "Replay the commands saved in this file first.")

        override fun execute(): Int = cli.state(session)
    }

    private class ScreensCommand<S, A>(private val cli: Cli<S, A>) : Subcommand(
        "screens",
        "List every screen path with its commands, arguments and help, including inherited commands.",
    ) {
        override fun execute(): Int = cli.screens()
    }

    private class DocsCommand<S, A>(private val cli: Cli<S, A>) : Subcommand(
        "docs",
        "Write ${cli.config.docsPath} from the command registry.",
        examples(
            "${cli.config.help.invocation} docs           # after changing a screen's agent declaration",
            "${cli.config.help.invocation} docs --check   # exit 1 if the file is stale (part of ${cli.config.help.invocation} check)",
        ),
    ) {
        private val check by option(help = "Only check that the file is up to date; exit 1 if it is stale.").flag()

        override fun execute(): Int = cli.docs(check)
    }

    private class TestCommand<S, A>(private val cli: Cli<S, A>) : Subcommand(
        "test",
        "Run scenario files (default: ${cli.config.scenariosPath}/*.appctl) and print pass/fail per file.",
        examples(
            "${cli.config.help.invocation} test",
            "${cli.config.help.invocation} test ${cli.config.help.scenarioPath ?: "${cli.config.scenariosPath}/<name>.appctl"}",
        ),
    ) {
        private val paths by argument(help = "Scenario files. Defaults to every ${cli.config.scenariosPath}/*.appctl.").multiple()

        override fun execute(): Int = cli.test(paths)
    }

    private class SnapshotsCommand<S, A>(private val cli: Cli<S, A>) : Subcommand(
        "snapshots",
        "Run the L3 screenshot tests (or re-record the reference images).",
        "Runs ${cli.config.gradle.snapshotsVerify.joinToString(" ").ifEmpty { "nothing: the config's gradle.snapshotsVerify is empty" }}. " +
            "A failure prints where the report is; look at the images before deciding.\n\n" +
            examples(
                "${cli.config.help.invocation} snapshots             # after a view change",
                "${cli.config.help.invocation} snapshots --record    # after an intended visual change; review the images",
            ),
    ) {
        private val record by option(help = "Re-record every reference image instead of comparing.").flag()

        override fun execute(): Int = cli.snapshots(record)
    }

    private class CheckCommand<S, A>(private val cli: Cli<S, A>) : Subcommand(
        "check",
        "Run the verification ladder: L0 build, L1 tests, L2 scenarios, docs check (--ui adds L3 and L4).",
        "Prints one line per stage and stops at the first failing stage. Full logs: ${cli.config.outputPath}/logs/.\n\n" +
            examples(
                "${cli.config.help.invocation} check          # before every commit",
                "${cli.config.help.invocation} check --ui     # also L3 screenshots and L4: the app on a device, a scenario through its agent bridge",
            ),
    ) {
        private val ui by option(
            help = "Add L3 (screenshot tests) and L4 (the seeded app on a device, a scenario through its agent bridge, a screenshot).",
        ).flag()
        private val device by option(help = "adb serial or AVD name for --ui.")

        override fun execute(): Int = cli.check(ui, device ?: cli.config.device)
    }

    private class AppLaunchCommand<S, A>(private val cli: Cli<S, A>) : Subcommand(
        "launch",
        "Build, install and start the app on a device, optionally already in a seeded state.",
        examples(
            "${cli.config.help.invocation} app launch --seed \"${cli.config.help.appSeed(0)}\"",
            "${cli.config.help.invocation} app launch --no-build --seed \"${cli.config.help.appSeed(1)}\"",
        ),
    ) {
        private val seed by option(help = "Commands to run before the first frame (appctl-seed).")
        private val device by option(help = "adb serial or AVD name.")
        private val latency by option(help = "Fixed mock latency in ms (default: the app's 300–800 ms).").int()
        private val clearSession by option("--clear-session", help = "Forget the saved session before launching.").flag()
        private val noBuild by option("--no-build", help = "Relaunch the installed app instead of building it.").flag()
        private val port by portOption()

        override fun execute(): Int = cli.appLaunch(seed, device ?: cli.config.device, latency, clearSession, !noBuild, port)
    }

    private class AppRunCommand<S, A>(private val cli: Cli<S, A>) : Subcommand(
        "run",
        "Run a script in the running app (headless-only commands such as advance are rejected).",
        examples(
            "${cli.config.help.invocation} app run \"${cli.config.help.appScript(0)}\"",
            "${cli.config.help.invocation} app run --json \"${cli.config.help.appScript(1)}\"",
        ),
    ) {
        private val script by argument(help = "Commands separated by ';' or newlines.")
        private val json by option(help = "Print a JSON array of steps.").flag()
        private val port by portOption()

        override fun execute(): Int = cli.appRun(script, json, port)
    }

    private class AppGetCommand<S, A>(private val cli: Cli<S, A>, name: String, private val path: String) : Subcommand(
        name,
        if (path == "/state") "Print the running app's root state." else "List screens, from the running app.",
        if (path == "/state") "Example: ${cli.config.help.invocation} app state --port ${BridgeDefaults.PORT}" else null,
    ) {
        private val port by portOption()

        override fun execute(): Int = cli.appGet(path, port)
    }
}

/** `--port`, shared by every `app` subcommand. */
private fun CoreCliktCommand.portOption() =
    option(help = "The port of the app's agent bridge (the app's agent-port).").int().default(BridgeDefaults.PORT)
