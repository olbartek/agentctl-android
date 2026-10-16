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

    /**
     * `\r\n` is one grapheme, so a check for `"\n"` never saw it: a CRLF script parsed as one command, and a CRLF file
     * starting with a comment was all comment, passing having run nothing (CONTRACT.md §1.1).
     */
    @Test
    fun crlfEndsALineLikeLF() {
        assertEquals(ScriptParser.parse("open 2\nsave\nexpect saved=true\n"), ScriptParser.parse("open 2\r\nsave\r\nexpect saved=true\r\n"))
        assertEquals(listOf("open 2", "save"), ScriptParser.parse("open 2\r\nsave\r\n").map { it.text })
        assertEquals(listOf(1, 2), ScriptParser.parse("open 2\r\nsave").map { it.line })
    }

    @Test
    fun crlfEndsAComment() {
        assertEquals(listOf(ScriptLine(2, "expect", "screen=nope")), ScriptParser.parse("# comment\r\nexpect screen=nope\r\n"))
        assertEquals(listOf("open 2", "save"), ScriptParser.parse("open 2 # comment\r\nsave").map { it.text })
    }

    @Test
    fun crlfEndsAQuote() {
        val error = assertFailsWith<ScriptError> { ScriptParser.parse("email \"alice\r\nsubmit\"") }
        assertEquals(ScriptError(1, 7, "unterminated quote"), error)
    }

    /** A `\r` not followed by `\n` is whitespace, not a line break, as in the reference. */
    @Test
    fun loneCarriageReturnIsWhitespace() {
        assertEquals(listOf(ScriptLine(1, "open", "2\rsave")), ScriptParser.parse("open\r2\rsave"))
        assertEquals(listOf("open 2", "save"), ScriptParser.parse("open 2\r\r\nsave\r").map { it.text })
        assertEquals(emptyList(), ScriptParser.parse("# comment\rsave"))
    }

    /**
     * Characters compare by canonical equivalence, as Swift's `Character ==` does: U+037E GREEK QUESTION MARK is
     * canonically `;`, so it separates commands as `;` does.
     */
    @Test
    fun aCanonicalSemicolonSeparates() {
        assertEquals(listOf("open 2", "save"), ScriptParser.parse("open 2\u037Esave").map { it.text })
        assertEquals(listOf("expect title=\"a\u037Eb\""), ScriptParser.parse("expect title=\"a\u037Eb\"").map { it.text })
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
        assertEquals("", ArgumentText.unquoted("\"\""))
        assertEquals("\"", ArgumentText.unquoted("\""))
    }

    /**
     * Only one quoted span from end to end is unquoted: the quote that closes the first one must be the last character
     * (CONTRACT.md §1.2). `"a"b"c"` used to come out as `abc`.
     */
    @Test
    fun unquotesOnlyOneWholeQuotedSpan() {
        for (asWritten in listOf("\"a\"b\"c\"", "\"a\"\"b\"", "\"a\"b", "a\"b\"", "\"a\\\"")) {
            assertEquals(asWritten, ArgumentText.unquoted(asWritten))
        }
        assertEquals("a \"b\" c", ArgumentText.unquoted("\"a \\\"b\\\" c\""))
        assertEquals("a\\", ArgumentText.unquoted("\"a\\\\\""))
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

    @Test
    fun aFileReadDropsALeadingByteOrderMark() {
        // Windows editors write one, as they write CRLF; Swift's UTF-8 decoding drops it.
        val file = java.io.File.createTempFile("bom", ".appctl").apply { deleteOnExit() }
        file.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "open 2\n".toByteArray())
        assertEquals("open 2\n", ScriptParser.read(file))
    }

    @Test
    fun anAppTestMarkerTrimsBlanksButNotACarriageReturn() {
        // As Swift's `.whitespaces`: a `\r` before the line's CRLF is part of the line.
        assertNull(AppTestSkip.reason("# app-test: skip\r\r\nopen 2"))
        assertEquals("because\r", AppTestSkip.reason("# app-test: skip because\r\r\nopen 2"))
        assertEquals("because", AppTestSkip.reason("\t#\u00A0app-test: skip because \nopen 2"))
    }
}
