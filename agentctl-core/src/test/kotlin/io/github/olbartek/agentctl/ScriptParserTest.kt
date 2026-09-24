package io.github.olbartek.agentctl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ScriptParserTest {
    @Test
    fun separatorsCommentsAndWhitespace() {
        val lines = ScriptParser.parse(
            """
            # a comment line
            login-as alice;  open 1003 ; cancel   # trailing comment

              expect screen=home/orders/1003
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                ScriptLine(2, "login-as", "alice"),
                ScriptLine(2, "open", "1003"),
                ScriptLine(2, "cancel", null),
                ScriptLine(4, "expect", "screen=home/orders/1003"),
            ),
            lines,
        )
    }

    @Test
    fun quotesProtectSeparatorsAndAreKept() {
        val lines = ScriptParser.parse("""password "a;b#c" ; name Alice Smith; expect name="Alice \"A\" Smith"""")
        assertEquals(listOf("password", "name", "expect"), lines.map { it.name })
        assertEquals("\"a;b#c\"", lines[0].argument)
        assertEquals("Alice Smith", lines[1].argument)
        assertEquals("""name="Alice \"A\" Smith"""", lines[2].argument)
    }

    @Test
    fun unterminatedQuoteIsAnError() {
        val error = assertFailsWith<ScriptError> { ScriptParser.parse("submit\nemail \"alice") }
        assertEquals(ScriptError(2, 7, "unterminated quote"), error)
        assertFailsWith<ScriptError> { ScriptParser.parse("email \"alice\nsubmit\"") }
    }

    /** CONTRACT.md §1.4: the column where the quote opened, counted in characters as a person sees them. */
    @Test
    fun columnsCountGraphemesNotUtf16Units() {
        val error = assertFailsWith<ScriptError> { ScriptParser.parse("open 👩‍👩‍👧; expect title=\"x") }
        assertEquals("line 1, column 22: unterminated quote", error.description)
        val contract = assertFailsWith<ScriptError> { ScriptParser.parse("open 2; expect title=\"Second") }
        assertEquals("line 1, column 22: unterminated quote", contract.description)
    }

    @Test
    fun emptyScripts() {
        assertEquals(emptyList(), ScriptParser.parse(""))
        assertEquals(emptyList(), ScriptParser.parse(" ; ;\n# only a comment\n"))
    }

    @Test
    fun argumentText() {
        assertEquals("a;b#c", ArgumentText.unquoted("\"a;b#c\""))
        assertEquals("Alice Smith", ArgumentText.unquoted("Alice Smith"))
        assertEquals("\"a\" \"b\"", ArgumentText.unquoted("\"a\" \"b\""))
        assertEquals("say \"hi\"", ArgumentText.unquoted("\"say \\\"hi\\\"\""))
        assertEquals(
            listOf("screen=x", "name=Alice Smith", "error=none"),
            ArgumentText.tokens("screen=x name=\"Alice Smith\"  error=none"),
        )
        assertEquals(listOf("a="), ArgumentText.tokens("a=\"\""))
    }

    @Test
    fun durations() {
        assertEquals(AgentDuration.ofMilliseconds(500), ScriptParser.parseDuration("500ms"))
        assertEquals(AgentDuration.ofSeconds(30), ScriptParser.parseDuration("30s"))
        assertEquals(AgentDuration.ofSeconds(300), ScriptParser.parseDuration("5m"))
        assertEquals(AgentDuration.ofSeconds(3600), ScriptParser.parseDuration("1h"))
        for (bad in listOf("s", "1.5s", "10", "-1s", "+1s", "1h30m", "１s")) assertNull(ScriptParser.parseDuration(bad), bad)
    }

    @Test
    fun durationsTooLargeToRepresentAreRejected() {
        assertNull(ScriptParser.parseDuration("9999999999999999h"))
        assertNull(ScriptParser.parseDuration("9223372036854775807m"))
        assertNull(ScriptParser.parseDuration("99999999999999999999s"))
        assertEquals(AgentDuration.ofSeconds(Long.MAX_VALUE), ScriptParser.parseDuration("${Long.MAX_VALUE}s"))
        assertEquals(
            AgentDuration.ofSeconds(Long.MAX_VALUE / 3600 * 3600),
            ScriptParser.parseDuration("${Long.MAX_VALUE / 3600}h"),
        )
        assertEquals(AgentDuration.ofMilliseconds(Long.MAX_VALUE), ScriptParser.parseDuration("${Long.MAX_VALUE}ms"))
    }
}
