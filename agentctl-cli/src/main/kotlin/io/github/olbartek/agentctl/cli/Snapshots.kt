package io.github.olbartek.agentctl.cli

import java.io.File

/**
 * L3: the screenshot tests (Roborazzi, Paparazzi or any Gradle task that compares against references), which the
 * config names as Gradle tasks. They run on the JVM, so unlike the reference's they need no device.
 */
internal class Snapshots(private val cli: Cli<*, *>, private val root: File) {
    data class Result(val ok: Boolean, val summary: String, val details: List<String>)

    fun run(record: Boolean): Result {
        val gradle = cli.config.gradle
        val tasks = if (record) gradle.snapshotsRecord else gradle.snapshotsVerify
        if (tasks.isEmpty()) {
            // Nothing to verify is not a failure of the app, as the reference's empty `snapshotPackages`; nothing to
            // record with is a failure of the command.
            return if (record) {
                Result(false, "no snapshot tasks", listOf("the config's gradle.snapshotsRecord is empty"))
            } else {
                Result(true, "no snapshot tasks configured", emptyList())
            }
        }
        val layout = Layout(root, cli.config.outputPath)
        val log = File(layout.logs, if (record) "L3-snapshots-record.log" else "L3-snapshots.log")
        val status = Shell.run(Gradle.command(root, tasks), root, log)
        if (status == 0) return Result(true, tasks.joinToString(" "), emptyList())
        val lines = (if (log.isFile) log.readText() else "").split("\n")
        val details = lines
            .filter { "FAILED" in it || "Screenshot" in it || "report" in it.lowercase() && "file://" in it || "What went wrong" in it }
            .take(40)
            .map { "  ${it.trim()}" }
        return Result(false, "${tasks.joinToString(" ")} failed", listOf("failed; log: ${log.path}") + details)
    }
}
