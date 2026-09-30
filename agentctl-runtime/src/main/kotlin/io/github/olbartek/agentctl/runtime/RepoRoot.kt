package io.github.olbartek.agentctl.runtime

import java.io.File

/**
 * Finds a host's repo root: the nearest directory, at or above a starting point, that contains the config's root
 * marker ([AppCtlConfig.rootMarker]).
 *
 * One walk for every caller, so the CLI (starting from the working directory) and a host's tests (starting from
 * theirs, which Gradle sets to the module's directory) agree on where `scenariosPath` and `docsPath` are resolved
 * from.
 */
public object RepoRoot {
    /**
     * The nearest directory at or above [start] that contains [marker] (a path relative to it, such as
     * `settings.gradle.kts`), or `null` when no ancestor does. A [start] that is a file begins the walk at its
     * directory.
     */
    public fun find(marker: String, start: File): File? {
        var directory: File? = start.absoluteFile.normalize().let { if (it.isFile) it.parentFile else it }
        while (directory != null) {
            if (File(directory, marker).exists()) return directory
            directory = directory.parentFile
        }
        return null
    }
}
