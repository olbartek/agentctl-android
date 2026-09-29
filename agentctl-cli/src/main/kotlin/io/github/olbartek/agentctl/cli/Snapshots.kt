package io.github.olbartek.agentctl.cli

import java.io.File

/**
 * L3: the screenshot tests (Roborazzi, Paparazzi or any Gradle task that compares against references). The config
 * names the modules that have them, and the tasks are found in each module's task list; it may also name tasks
 * outright. They run on the JVM, so unlike the reference's they need no device.
 */
internal class Snapshots(private val cli: Cli<*, *>, private val root: File) {
    data class Result(val ok: Boolean, val summary: String, val details: List<String>)

    fun run(record: Boolean): Result {
        val gradle = cli.config.gradle
        val named = if (record) gradle.snapshotsRecord else gradle.snapshotsVerify
        if (gradle.snapshotModules.isEmpty() && named.isEmpty()) {
            // Nothing to verify is not a failure of the app, as the reference's empty `snapshotPackages`; nothing to
            // record with is a failure of the command.
            return if (record) {
                Result(false, "no snapshot tasks", listOf("the config's gradle.snapshotModules and gradle.snapshotsRecord are empty"))
            } else {
                Result(true, "no snapshot tasks configured", emptyList())
            }
        }
        val layout = Layout(root, cli.config.outputPath)
        val tasks = named.toMutableList()
        val problems = mutableListOf<String>()
        for (module in gradle.snapshotModules) {
            val log = File(layout.logs, "L3-tasks-${logName(module)}.log")
            val status = Shell.run(Gradle.command(root, listOf(taskPath(module, "tasks"), "--all")), root, log, timeoutSeconds = 600)
            if (status != 0) {
                problems.add("$module: cannot list its tasks; log: ${log.path}")
                continue
            }
            val listed = taskNames(log.readText())
            val found = snapshotTasks(listed, record)
            if (found.isEmpty()) {
                val offered = listed.filter { it.contains("Roborazzi") || it.contains("Paparazzi") }
                problems.add(
                    "$module: no screenshot task to ${if (record) "record" else "verify"} with; its screenshot tasks: " +
                        offered.joinToString(", ").ifEmpty { "none (is the Roborazzi or Paparazzi plugin applied?)" },
                )
                continue
            }
            tasks.addAll(found.map { taskPath(module, it) })
        }
        if (problems.isNotEmpty()) return Result(false, "no snapshot tasks found", problems)
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

    companion object {
        /** The tools whose tasks L3 looks for, in order of preference. */
        private val tools = listOf("Roborazzi", "Paparazzi")

        /**
         * The screenshot tasks to run in a module, from the task names it lists: the first tool's that has any, and of
         * its `verify<Tool><Variant>` (or `record…`) tasks the `Debug` variant's, else every `…Debug` one (a module
         * with product flavors), else the only one. Empty when none of these fits — several variants and none of
         * them debug, say.
         */
        fun snapshotTasks(listed: List<String>, record: Boolean): List<String> {
            val verb = if (record) "record" else "verify"
            for (tool in tools) {
                val prefix = verb + tool
                val candidates = listed.filter { it.startsWith(prefix) && (it.length == prefix.length || it[prefix.length].isUpperCase()) }
                    .distinct()
                if (candidates.isEmpty()) continue
                if ("${prefix}Debug" in candidates) return listOf("${prefix}Debug")
                candidates.filter { it.endsWith("Debug") }.takeIf { it.isNotEmpty() }?.let { return it.sorted() }
                return if (candidates.size == 1) candidates else emptyList()
            }
            return emptyList()
        }

        /** The task names in the output of `gradlew <module>:tasks --all`: each task line starts with one. */
        fun taskNames(output: String): List<String> = output.lines().mapNotNull { line ->
            val name = line.substringBefore(" - ").trim()
            name.takeIf { !line.startsWith(" ") && it.matches(taskName) }
        }

        private val taskName = Regex("[a-z][A-Za-z0-9]*")

        /** A task of a module: `:feature:items` + `tasks` → `:feature:items:tasks`, and the root `:` → `:tasks`. */
        fun taskPath(module: String, task: String): String = if (module == ":") ":$task" else "$module:$task"

        /** A module path as part of a file name: `:feature:items` → `feature-items`. */
        fun logName(module: String): String = module.trim(':').replace(':', '-').ifEmpty { "root" }

        /** A module's directory relative to the root, by Gradle's default layout: `:feature:items` → `feature/items`. */
        fun directory(module: String): String = module.trim(':').replace(':', '/').ifEmpty { "." }
    }
}
