package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.runtime.RunStatus
import io.github.olbartek.agentctl.runtime.ScenarioRunner
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/** What this guards: how a script's text is read through the real CLI, where CONTRACT.md §1–§2 and §5 pin it down. */
class ScriptTextTest {
    /** CONTRACT.md §2.1: an `=` only inside quotes is not a pair; the line's shape is wrong, so exit 2. */
    @Test
    fun anExpectWhoseOnlyEqualsIsQuotedIsAUsageError() {
        val result = tinyctl("run", "open 2; expect \"title=Second item\"")
        assertEquals(2, result.status, result.combined)
        assertEquals(
            "> expect \"title=Second item\"\n  screen=items/2 title=\"Second item\" saved=false cooldown=0\n" +
                "  FAIL expected key=value, got 'title=Second item'\n",
            result.out.substringAfter("cooldown=0\n"),
        )
    }

    /** `open 2` then a byte that is not UTF-8: a file Swift's `String(contentsOf:encoding: .utf8)` refuses. */
    private fun notUtf8(prefix: String, suffix: String): File =
        Files.createTempFile(prefix, suffix).toFile().apply { writeBytes("open 2\n".toByteArray() + byteArrayOf(0xFF.toByte()) + "\n".toByteArray()) }

    /**
     * CONTRACT.md §5: a scenario file that cannot be read is exit 3, and one that is not UTF-8 cannot be read. It used to
     * run, with U+FFFD in place of the bad byte.
     */
    @Test
    fun aScenarioThatIsNotUtf8CannotBeRead() = runBlocking {
        val file = notUtf8("latin1", ".appctl")
        val result = ScenarioRunner.run(file) { TinyAppConfig.headless().makeRunner() }
        assertEquals(RunStatus.INTERNAL_ERROR, result.status)
        assertEquals("cannot read ${file.path}", result.message)
        assertEquals(emptyList(), result.steps)

        val cli = tinyctl("test", file.path)
        assertEquals(3, cli.status, cli.combined)
        assertEquals("FAIL ${file.nameWithoutExtension}\n  cannot read ${file.path}\n0 passed, 1 failed\n", cli.out)
    }

    /** A `--session` file that is not UTF-8 replays nothing, as the reference reads an unreadable one as empty. */
    @Test
    fun aSessionThatIsNotUtf8CannotBeRead() {
        // As any other unreadable session file (CONTRACT.md §5): one line, exit 3, nothing run.
        val session = notUtf8("session", ".session")
        val result = tinyctl("run", "--session", session.path, "expect screen=items")
        assertEquals(3, result.status, result.combined)
        assertEquals("error: cannot read ${session.path}: not valid UTF-8\n", result.err)
        assertEquals("", result.out)
    }
}
