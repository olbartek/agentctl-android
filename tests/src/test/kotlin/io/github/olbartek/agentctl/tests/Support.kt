package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.cli.AgentCtl
import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.runtime.AppCtlConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/** The repository root, where CONTRACT.md, the scenarios and the committed docs live. */
val repositoryRoot: File = File(System.getProperty("agentctl.root") ?: error("agentctl.root is not set"))

val scenariosDirectory: File get() = File(repositoryRoot, TinyAppConfig.appCtl.scenariosPath)

/** What one CLI invocation printed, and its exit code. */
data class CliResult(val out: String, val err: String, val status: Int) {
    /** Stdout, then stderr: what a terminal shows for the examples in CONTRACT.md. */
    val combined: String get() = out + err
}

/** Runs the CLI in-process against `config`, from the repository root. */
fun <S, A> cli(vararg args: String, config: AppCtlConfig<S, A>, environment: Map<String, String> = emptyMap(), workingDirectory: File = repositoryRoot): CliResult {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val status = AgentCtl.run(
        config,
        args.toList(),
        PrintStream(out, true, Charsets.UTF_8),
        PrintStream(err, true, Charsets.UTF_8),
        environment,
        workingDirectory,
    )
    return CliResult(out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8), status)
}

/** Runs TinyApp's CLI in-process. */
fun tinyctl(vararg args: String): CliResult = cli(*args, config = TinyAppConfig.appCtl)

/** Splits a shell command line the way `sh` does for the quoting the documentation uses. */
fun shellWords(line: String): List<String> {
    val words = mutableListOf<String>()
    val current = StringBuilder()
    var inWord = false
    var index = 0
    while (index < line.length) {
        val character = line[index]
        when {
            character == '\'' -> {
                val end = line.indexOf('\'', index + 1)
                current.append(line, index + 1, end)
                index = end
                inWord = true
            }
            character == '"' -> {
                index += 1
                while (line[index] != '"') {
                    if (line[index] == '\\' && line[index + 1] in "\"\\$`") index += 1
                    current.append(line[index])
                    index += 1
                }
                inWord = true
            }
            character.isWhitespace() -> {
                if (inWord) words.add(current.toString())
                current.setLength(0)
                inWord = false
            }
            else -> {
                current.append(character)
                inWord = true
            }
        }
        index += 1
    }
    if (inWord) words.add(current.toString())
    return words
}
