package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.runtime.HttpParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The bridge's request target is read as the reference reads it: `URLComponents(string: target)`, then `.path` and
 * `.queryItems` (a missing value is ""; a repeated name keeps the last). Every expectation here was printed by
 * `URLComponents` on macOS 26 (Swift 6.3.3).
 */
class BridgeTargetTest {
    private fun target(target: String): Pair<String, Map<String, String>> =
        when (val result = HttpParser.parse("GET $target HTTP/1.1\r\n\r\n".toByteArray())) {
            is HttpParser.Result.Request -> result.request.path to result.request.query
            else -> fail("$target: $result")
        }

    private fun check(target: String, path: String, vararg query: Pair<String, String>) =
        assertEquals(path to mapOf(*query), target(target), target)

    @Test
    fun charactersURLComponentsEncodesKeepTheTargetApart() {
        check("/run?format=json&x=a|b", "/run", "format" to "json", "x" to "a|b")
        check("/run?format=json&x={}", "/run", "format" to "json", "x" to "{}")
        check("/run?format=json&x=\"q\"", "/run", "format" to "json", "x" to "\"q\"")
        check("/run?x=[1]", "/run", "x" to "[1]")
        check("/run?x=a^b", "/run", "x" to "a^b")
        check("/run?x=`", "/run", "x" to "`")
        check("/run?x=<b>", "/run", "x" to "<b>")
        check("/run?x=é", "/run", "x" to "é")
        check("/run?x=\u007F", "/run", "x" to "\u007F")
        check("/a|b?x=1", "/a|b", "x" to "1")
        check("/a{b}", "/a{b}")
        check("/a\\b", "/a\\b")
        check("/é?x=1", "/é", "x" to "1")
    }

    @Test
    fun anInvalidEscapeKeepsItsWholeComponentLiteral() {
        check("/snapshot?q=%ZZ", "/snapshot", "q" to "%ZZ")
        check("/run?format=json&q=%", "/run", "format" to "json", "q" to "%")
        check("/run?x=%2", "/run", "x" to "%2")
        check("/run?a=%4g", "/run", "a" to "%4g")
        check("/run?x=%41%", "/run", "x" to "%41%")
        // The query is one component: one bad escape, or one character URLComponents encodes, leaves all of it as sent.
        check("/run?a=%41&b=%ZZ", "/run", "a" to "%41", "b" to "%ZZ")
        check("/run?a=%41&b={}", "/run", "a" to "%41", "b" to "{}")
        check("/run?a=%FF&b=%ZZ", "/run", "a" to "%FF", "b" to "%ZZ")
        check("/run?a=%41&b=|", "/run", "a" to "%41", "b" to "|")
        // ...and the path is another.
        check("/a%ZZ?x=1", "/a%ZZ", "x" to "1")
        check("/a%ZZ/%41?x=%41", "/a%ZZ/%41", "x" to "A")
        check("/r%41?a=%ZZ", "/rA", "a" to "%ZZ")
        check("/r{}?a=%41", "/r{}", "a" to "A")
        check("/r%41|", "/r%41|")
        // The fragment is never read, nor checked.
        check("/run?a=%41#{}", "/run", "a" to "A")
        check("/run?a=%41#%", "/run", "a" to "A")
    }

    @Test
    fun invalidUtf8DecodesToEmpty() {
        check("/run?format=json&x=%FF", "/run", "format" to "json", "x" to "")
        check("/run?x=%C3", "/run", "x" to "")
        check("/run?x=%e9", "/run", "x" to "")
        check("/run?x=a%FFb", "/run", "x" to "")
        check("/run?x=%ED%A0%80", "/run", "x" to "")
        check("/run?x=%C0%80", "/run", "x" to "")
        check("/run?a=%41&b=%FF", "/run", "a" to "A", "b" to "")
        check("/run?%FF=1", "/run", "" to "1")
        check("/a%FF?x=1", "", "x" to "1")
        check("/%FF", "")
    }

    @Test
    fun escapesThatDecode() {
        check("/run?x=a%20b", "/run", "x" to "a b")
        check("/run?x=%E2%9C%93", "/run", "x" to "✓")
        check("/run?x=%f0%9f%98%80", "/run", "x" to "\uD83D\uDE00")
        check("/run?x=%c3%a9", "/run", "x" to "é")
        check("/run?x=%25ZZ", "/run", "x" to "%ZZ")
        check("/run?%78=1", "/run", "x" to "1")
        check("/run?x=%2B", "/run", "x" to "+")
        check("/run?x=%00", "/run", "x" to "\u0000")
        check("/a%2Fb?x=1", "/a/b", "x" to "1")
        check("/a%2fb", "/a/b")
        check("/r%20un", "/r un")
        check("/%7E", "/~")
    }

    @Test
    fun queryItems() {
        check("/snapshot", "/snapshot")
        check("/run?", "/run")
        check("/run?#", "/run")
        check("/run?x=", "/run", "x" to "")
        check("/run?x", "/run", "x" to "")
        check("/run?x=1&x=2", "/run", "x" to "2")
        check("/run?x=a+b", "/run", "x" to "a+b")
        check("/run?x=a=b", "/run", "x" to "a=b")
        check("/run?=v", "/run", "" to "v")
        check("/run?x=1&&y=2", "/run", "x" to "1", "" to "", "y" to "2")
        check("/run?x=1&", "/run", "x" to "1", "" to "")
        check("/run?&", "/run", "" to "")
        check("/run?x=1;y=2", "/run", "x" to "1;y=2")
        check("/run??x=1", "/run", "?x" to "1")
        check("/a?b?c=d", "/a", "b?c" to "d")
        check("/x?y=1:2", "/x", "y" to "1:2")
        check("/~?x=~", "/~", "x" to "~")
    }

    @Test
    fun fragments() {
        check("/run?x=1#frag", "/run", "x" to "1")
        check("/run#frag?x=1", "/run")
        check("/run?x=a#b#c", "/run", "x" to "a")
        check("/run?x=a#", "/run", "x" to "a")
        check("/run#", "/run")
        check("#f", "")
    }

    @Test
    fun schemesAndAuthorities() {
        check("http://host:8080/run?x=1", "/run", "x" to "1")
        check("//host/run?x=1", "/run", "x" to "1")
        check("///run?x=1", "/run", "x" to "1")
        check("http:/run?x=1", "/run", "x" to "1")
        check("http:run?x=1", "run", "x" to "1")
        check("A+:x?y=1", "x", "y" to "1")
        check("x:/%41", "/A")
        check("a:/b:c", "/b:c")
        check("http://h", "")
        check("http:", "")
        check("//", "")
        check("//host?x=1", "", "x" to "1")
        check("//h?x=1#f", "", "x" to "1")
        check("//a@b:1/x?y=1", "/x", "y" to "1")
        check("//u@v@h/x", "/x")
        check("//u%ZZ@h/x?y=%41", "/x", "y" to "A")
        check("//[::1]/x", "/x")
        check("//[::1]:80/x", "/x")
        check("//[zz]/x", "/x")
        check("//[]/x", "/x")
        check("//host:/x", "/x")
        check("//:80/x", "/x")
        check("//host:99999/x", "/x")
        check("//h%41/x", "/x")
        check("//h%FF/x", "/x")
        check("//é/x", "/x")
        check("//h~!$&'()*+,;=/x", "/x")
        check("//[%40,=!*]/x", "/x")
        check("//[%40>:]//x", "//x")
        check("//[a%{|}]/x", "/x")
        check("//é!/x", "/x")
        check("//é./x", "/x")
        check("//a--é/x", "/x")
        check("//h:1/x?y=%ZZ&z=%41", "/x", "y" to "%ZZ", "z" to "%41")
        // No scheme: a colon after a slash, or an empty name before it.
        check("/a:b?x=1", "/a:b", "x" to "1")
        check("/:x", "/:x")
        check(":x", ":x")
        check(":/x?y=1", ":/x", "y" to "1")
        check("run?x=1", "run", "x" to "1")
        check("?x=1", "", "x" to "1")
        check("*", "*")
        check("%", "%")
        // A name that is no scheme is refused (below), unless it holds '@', '[' or ']'.
        check("a@b:c", "a@b:c")
        check("-@a:b", "-@a:b")
        check("%41@:x", "A@:x")
        check("1@a:b?x=%41", "1@a:b", "x" to "A")
        check("a]b:c?x=%41", "a]b:c", "x" to "A")
        check("-[a:b", "-[a:b")
        check("-@A:%FF;!,%41", "")
        check("=@<:%^`#", "=@<:%^`")
    }

    @Test
    fun whenURLComponentsGivesNilTheWholeTargetIsThePath() {
        for (target in listOf(
            "1a:b", "-a:b", "a_b:c", "a{b:c", "é:x", "%41:x",
            "//host:abc/run?x=1", "//h:1:2/x", "//h:1a/x", "//h:-1/x", "//h:%31/x", "//h:80x/x",
            "//[::1/x", "//h]/x", "//[>]/x", "//[>%]/x", "//[%]]/x",
            // A host that is not ASCII must pass IDNA's label rules.
            "//é-/x", "//-é/x", "//é..a/x", "//é.-a/x", "//xn--é/x", "//ab--cd.é/x", "//-.é", "//[::1]x/x", "//h%ZZ/x", "//h{}/x", "http://[bad",
        )) {
            check(target, target)
        }
    }
}
