package io.github.olbartek.agentctl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExpectationTest {
    private val snapshot = StepSnapshot(
        screen = "auth/otp/code",
        summary = listOf(SummaryItem("email", "alice@example.com"), SummaryItem("resendIn", 29), SummaryItem("name", "Alice Smith")),
        calls = listOf("auth.sendOTP"),
        error = null,
        pending = 1,
    )

    @Test
    fun parsesPairs() {
        val expectation = Expectation.parse("""screen=auth/login name="Alice Smith" call=auth.login""")
        assertEquals(
            listOf(
                Expectation.Pair("screen", "auth/login"),
                Expectation.Pair("name", "Alice Smith"),
                Expectation.Pair("call", "auth.login"),
            ),
            expectation.pairs,
        )
    }

    @Test
    fun rejectsMalformedArguments() {
        assertEquals(
            "expect needs at least one key=value pair",
            assertFailsWith<ExpectationSyntaxError> { Expectation.parse(null) }.message,
        )
        assertEquals("expected key=value, got 'screen'", assertFailsWith<ExpectationSyntaxError> { Expectation.parse("screen") }.message)
        assertFailsWith<ExpectationSyntaxError> { Expectation.parse("=value") }
    }

    @Test
    fun passingExpectation() {
        val expectation = Expectation.parse(
            """screen=auth/otp/code resendIn=29 name="Alice Smith" call=auth.sendOTP error=none pending=1""",
        )
        assertEquals(emptyList(), expectation.evaluate(snapshot))
    }

    @Test
    fun failureMessages() {
        val expectation = Expectation.parse("screen=auth/login resendIn=30 call=auth.login error=invalidCode pending=0 nope=1")
        assertEquals(
            listOf(
                "expected screen=auth/login, got screen=auth/otp/code",
                "expected resendIn=30, got resendIn=29",
                "expected call=auth.login, got calls=auth.sendOTP",
                "expected error=invalidCode, got error=none",
                "expected pending=0, got pending=1",
                "unknown key 'nope' on auth/otp/code; available: screen, call, error, pending, email, resendIn, name",
            ),
            expectation.evaluate(snapshot),
        )
    }

    @Test
    fun callWithNoCalls() {
        val quiet = snapshot.copy(calls = emptyList())
        assertEquals(listOf("expected call=auth.login, got calls=none"), Expectation.parse("call=auth.login").evaluate(quiet))
    }
}

class StepFormatterTest {
    @Test
    fun textMatchesSpec() {
        val step = StepRecord(
            command = "submit",
            screen = "home/orders",
            summary = listOf(SummaryItem("orders", 3), SummaryItem("loading", false)),
            calls = listOf("auth.login", "session.save", "orders.fetchOrders"),
            error = null,
            pending = 0,
        )
        assertEquals(
            "> submit\n  screen=home/orders orders=3 loading=false calls=auth.login,session.save,orders.fetchOrders",
            StepFormatter.text(step),
        )
    }

    @Test
    fun textWithErrorPendingFailureAndQuoting() {
        val step = StepRecord(
            command = "expect resendIn=30",
            screen = "auth/otp/code",
            summary = listOf(SummaryItem("name", "Alice Smith"), SummaryItem("resendIn", 29), SummaryItem("empty", "")),
            calls = emptyList(),
            error = "resendNotAvailable",
            pending = 1,
            settled = false,
            ok = false,
            message = "expected resendIn=30, got resendIn=29\nsecond line",
        )
        assertEquals(
            """
            > expect resendIn=30
              screen=auth/otp/code name="Alice Smith" resendIn=29 empty="" error=resendNotAvailable pending=1 settled=false
              FAIL expected resendIn=30, got resendIn=29
              FAIL second line
            """.trimIndent(),
            StepFormatter.text(step),
        )
    }

    @Test
    fun json() {
        val step = StepRecord(
            command = "open 1003",
            screen = "home/orders/1003",
            summary = listOf(SummaryItem("status", "pending")),
            calls = listOf("orders.fetchOrder"),
            error = null,
            pending = 0,
        )
        assertEquals(
            """
            [
              {
                "calls" : [
                  "orders.fetchOrder"
                ],
                "command" : "open 1003",
                "error" : null,
                "ok" : true,
                "pending" : 0,
                "screen" : "home/orders/1003",
                "settled" : true,
                "summary" : {
                  "status" : "pending"
                }
              }
            ]
            """.trimIndent(),
            StepFormatter.json(listOf(step)),
        )
    }

    /** CONTRACT.md §8.4, byte for byte. */
    @Test
    fun jsonParseFailure() {
        assertEquals(
            "{\n  \"error\" : \"parse error: line 1, column 8: unterminated quote\",\n  \"steps\" : [\n\n  ]\n}",
            StepFormatter.json(emptyList(), "parse error: line 1, column 8: unterminated quote"),
        )
    }

    @Test
    fun jsonEscapesAndKeepsSlashes() {
        val step = StepRecord("a", "x/y", emptyList(), emptyList(), "e", 2, ok = false, message = "say \"hi\"\n\\")
        val json = StepFormatter.json(listOf(step))
        assertTrue(json.contains("\"screen\" : \"x/y\""))
        assertTrue(json.contains("\"message\" : \"say \\\"hi\\\"\\n\\\\\""))
        assertTrue(json.contains("\"summary\" : {\n\n    }"))
    }
}

class DocsRendererTest {
    private fun render(text: DocsText): String =
        DocsRenderer.render(emptyList(), AgentRegistry.runtimeCommands(text.mockExample), emptyList(), text)

    @Test
    fun theRenderedProseNamesNoHostApp() {
        val markdown = render(DocsText(title = "Docs", intro = "An app.", usageExamples = emptyList(), invocation = "xctl", appendix = emptyList()))
        val stripped = markdown.replace(".appctl", "").replace("-appctl-seed", "")
        assertTrue("appctl" !in stripped)
        for (word in listOf("alice", "login-as", "home/orders", "auth.login", "Alice Smith", "> submit", "orders.fetchOrders")) {
            assertTrue(word !in markdown, "the generated docs still name '$word'")
        }
        assertTrue("`xctl docs`" in markdown)
        assertTrue("`xctl test`" in markdown)
        assertTrue("  > <command>" in markdown)
        assertTrue("e.g. mock <client.method> <error>." in markdown)
    }

    @Test
    fun aHostRestoresItsOwnWording() {
        val text = DocsText(
            title = "Agent commands",
            intro = "Every screen can be driven with the same commands.",
            usageExamples = emptyList(),
            invocation = "./appctl",
            exampleCommand = "submit",
            exampleStep = "screen=home/orders orders=3 loading=false calls=auth.login,session.save,orders.fetchOrders",
            mockExample = "mock orders.fetchOrders network",
            appendix = emptyList(),
        )
        val lines = render(text).split("\n")
        assertTrue("  > submit" in lines)
        assertTrue("    screen=home/orders orders=3 loading=false calls=auth.login,session.save,orders.fetchOrders" in lines)
        assertTrue("Scenarios in `scenarios/*.appctl` use the same syntax plus `expect` lines; `./appctl test` runs them." in lines)
        assertTrue(
            "| `mock <client.method> <error>` | Make the next call to that method fail, e.g. mock orders.fetchOrders network. |" in lines,
        )
    }

    @Test
    fun theScreensListingCarriesTheHostsMockExample() {
        val listing = ScreensRenderer.render(emptyList(), "mock orders.fetchOrders network")
        assertTrue("Make the next call to that method fail, e.g. mock orders.fetchOrders network." in listing)
        assertTrue("orders.fetchOrders" !in ScreensRenderer.render(emptyList()))
    }
}
