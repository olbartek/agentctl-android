package io.github.olbartek.agentctl

/**
 * A scenario's request to be left out of `app test`, the run through the app on a device.
 *
 * A few scenarios hold headlessly but not in a running app: a first `expect` on the launch's own calls (which
 * `app launch` has already made), a countdown's exact value (it also ticks in real time), or a date that is in the
 * future only against the headless fixed date. A comment line says so, with a reason, anywhere in the file:
 *
 * ```
 * # app-test: skip the launch's calls are made before the script starts
 * ```
 *
 * `# appctl-sim: skip <reason>` is accepted as well: the marker's name in the scripts that ran scenarios this way
 * before `app test` existed.
 */
public object AppTestSkip {
    private val markers = listOf("app-test:", "appctl-sim:")

    /**
     * The reason [source] gives for skipping it in the app, or `null` if it gives none. A marker without a reason
     * still skips, with "no reason given".
     */
    public fun reason(source: String): String? {
        for (rawLine in source.split("\n")) {
            val line = rawLine.trim()
            if (!line.startsWith("#")) continue
            val comment = line.drop(1).trim()
            for (marker in markers) {
                if (!comment.startsWith(marker)) continue
                val directive = comment.drop(marker.length).trim()
                if (directive != "skip" && !directive.startsWith("skip ")) continue
                val reason = directive.drop("skip".length).trim()
                return reason.ifEmpty { "no reason given" }
            }
        }
        return null
    }
}
