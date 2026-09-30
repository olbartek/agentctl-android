package io.github.olbartek.agentctl.tests

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What this guards: the wrapper hosts copy (`Templates/appctl`) finds a JDK 17 or newer by itself: JAVA_HOME's, else
 * the `java` on PATH, else Homebrew's openjdk@21, and says so (exit 3) when there is none. It runs in a fake repository
 * whose `gradlew` does nothing and whose CLI prints the JAVA_HOME it got; the JDKs and `brew` are fakes too, and PATH
 * holds nothing else but the tools the script uses.
 */
class WrapperTest {
    private val sandbox = Files.createTempDirectory("wrapper").toFile()
    private val repo = File(sandbox, "repo")
    private val bin = File(sandbox, "bin").apply { mkdirs() }

    init {
        // Named as a host names it; the template's module is `:appctl`, whose directory is `appctl`.
        File(repo, "fixturectl").apply {
            parentFile.mkdirs()
            writeText(File(repositoryRoot, "Templates/appctl").readText())
            setExecutable(true)
        }
        script(File(repo, "gradlew"), "exit 0")
        script(File(repo, "appctl/build/install/appctl/bin/appctl"), "echo \"JAVA_HOME=\${JAVA_HOME:-unset}\"")
        for (tool in listOf("sed", "head", "dirname", "tr", "basename")) {
            val path = listOf("/usr/bin/$tool", "/bin/$tool").first { File(it).exists() }
            Files.createSymbolicLink(File(bin, tool).toPath(), File(path).toPath())
        }
    }

    @AfterTest
    fun removeTheSandbox() {
        sandbox.deleteRecursively()
    }

    private fun script(file: File, body: String) = file.apply {
        parentFile.mkdirs()
        writeText("#!/bin/sh\n$body\n")
        setExecutable(true)
    }

    /** A JDK home whose `java -version` reports [version], as a real one does (on stderr). */
    private fun jdk(name: String, version: String): File = File(sandbox, name).also {
        script(File(it, "bin/java"), "echo '$version' >&2")
    }

    private fun run(javaHome: File? = null): Pair<Int, String> {
        val process = ProcessBuilder("/bin/sh", File(repo, "fixturectl").path).redirectErrorStream(true).apply {
            environment().clear()
            environment()["PATH"] = bin.path
            javaHome?.let { environment()["JAVA_HOME"] = it.path }
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    @Test
    fun javaHomesJdkIsUsedWhenItIsNewEnough() {
        val home = jdk("jdk21", "openjdk version \"21.0.2\" 2024-01-16")
        assertEquals(0 to "JAVA_HOME=${home.path}\n", run(home))
    }

    @Test
    fun anOldJavaHomeGivesWayToTheJavaOnPath() {
        val old = jdk("jdk8", "java version \"1.8.0_402\"")
        script(File(bin, "java"), "echo 'openjdk version \"17.0.10\" 2024-01-16' >&2")
        assertEquals(0 to "JAVA_HOME=unset\n", run(old))
    }

    @Test
    fun withoutAnyJavaHomebrewsOpenjdk21IsUsed() {
        val prefix = File(sandbox, "homebrew/opt/openjdk@21")
        jdk("homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home", "openjdk version \"21.0.5\" 2024-10-15")
        script(File(bin, "brew"), "[ \"\$1 \$2\" = '--prefix openjdk@21' ] && echo '${prefix.path}'")
        assertEquals(0 to "JAVA_HOME=${prefix.path}/libexec/openjdk.jdk/Contents/Home\n", run())
    }

    /** An unset JAVA_HOME is not `/bin/java`, which exists on a Linux machine with a JDK installed. */
    @Test
    fun withoutJavaHomeOrAnyJavaItSaysSo() {
        assertEquals(3 to "fixturectl: no JDK 17 or newer (JAVA_HOME: unset); set JAVA_HOME to one, or: brew install openjdk@21\n", run())
    }

    @Test
    fun withNoJdkAtAllItSaysSo() {
        val old = jdk("jdk11", "openjdk version \"11.0.22\" 2024-01-16")
        assertEquals(
            3 to "fixturectl: no JDK 17 or newer (JAVA_HOME: ${old.path}); set JAVA_HOME to one, or: brew install openjdk@21\n",
            run(old),
        )
    }
}
