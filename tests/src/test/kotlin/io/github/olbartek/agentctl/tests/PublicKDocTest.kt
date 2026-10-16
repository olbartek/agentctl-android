package io.github.olbartek.agentctl.tests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What this guards: standing rule 3, every public declaration in a library module has a KDoc. `explicitApi()` only
 * forces the `public` modifier; this reads the library modules' `src/main` and lists each declaration marked `public`
 * (a class, interface, object, function, property or typealias) with no KDoc directly above it. Annotations may sit
 * between the KDoc and the declaration.
 *
 * Not checked: overrides (the overridden declaration carries the KDoc), companion objects, secondary constructors,
 * accessors, constructor parameters (the class's KDoc describes its constructor, and a property declared there
 * may carry its own), and anything inside a private or internal class or a function body.
 */
class PublicKDocTest {
    @Test
    fun everyPublicDeclarationHasAKDoc() {
        val modules = listOf("agentctl-core", "agentctl-runtime", "agentctl-cli", "agentctl-bridge", "agentctl-test-support")
        val sources = modules.flatMap { module ->
            File(repositoryRoot, "$module/src/main").walk().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }
        }
        check(sources.size > 40) { "found only ${sources.size} library sources under $repositoryRoot" }
        val undocumented = sources.flatMap { file ->
            undocumentedDeclarations(file.readText()).map { "${file.relativeTo(repositoryRoot).path}:$it" }
        }
        assertEquals(emptyList(), undocumented, "public declarations without a KDoc (standing rule 3)")
    }

    /** The scanner itself, on the shapes the library uses. */
    @Test
    fun theScannerFindsWhatItShould() {
        val source = """
            /** Documented. */
            public class A(
                /** A property, documented. */
                public val documented: Int,
                public val obvious: String = "{",
            ) {
                /** Documented. */
                @Suppress("x")
                @JvmName("y") public fun annotated(): Int = 1

                public fun missing(): Unit = run { }

                public override fun toString(): String = "public fun inAString()"

                public companion object {
                    public val inCompanion: Int = 1
                }
            }

            // public fun inAComment()
            private class Hidden {
                public fun insidePrivate() { }
            }

            internal object AlsoHidden { public val x: Int = 1 }

            public fun body() {
                val v = it.value
                public val afterAValue: Int = 1
                val anonymous = object { public fun local() { } }
            }

            public typealias Alias = String
        """.trimIndent()
        assertEquals(
            listOf("11: missing", "16: inCompanion", "27: body", "33: Alias"),
            undocumentedDeclarations(source),
        )
    }
}

private val declarationKeyword = Regex("""\b(class|interface|object|fun|val|var|typealias|constructor|get|set)\b""")
private val identifier = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

/**
 * `line: name` for each declaration in [source] marked `public` without a KDoc directly above it. Comments and
 * string literals are blanked first, so their braces and words never count.
 */
private fun undocumentedDeclarations(source: String): List<String> {
    val (code, kdocEnds) = blankCommentsAndStrings(source)
    val found = mutableListOf<String>()

    // Each open brace's scope: public (true) unless it is the body of a non-public class or of a function.
    val scopes = ArrayDeque(listOf(true))
    var parens = 0
    // A class or function header seen but whose body has not opened: its paren depth and whether its body is public.
    var pendingBody: Pair<Int, Boolean>? = null
    var i = 0
    while (i < code.length) {
        val c = code[i]
        when {
            c == '(' -> parens++
            c == ')' -> parens--
            c == '{' -> {
                val pending = pendingBody
                scopes.addLast(if (pending != null && pending.first == parens) pending.second else scopes.last())
                if (pending?.first == parens) pendingBody = null
            }
            c == '}' -> if (scopes.size > 1) scopes.removeLast()
            c.isLetter() && (i == 0 || !code[i - 1].isJavaIdentifierPart() && code[i - 1] != '.') -> {
                val word = identifier.matchAt(code, i)!!.value
                val modifiers = modifiersAt(code, i)
                if (modifiers != null) {
                    val (names, keyword, keywordAt) = modifiers
                    val inPublicScope = scopes.last()
                    val inConstructor = pendingBody?.let { parens > it.first } ?: false
                    if (!inConstructor) when (keyword) {
                        "class", "interface", "object" ->
                            pendingBody = parens to (inPublicScope && "private" !in names && "internal" !in names)
                        // A function's body, or a property's accessors or initializer, is never API.
                        "fun", "val", "var" -> pendingBody = parens to false
                    }
                    if ("public" in names && inPublicScope && !inConstructor && "override" !in names &&
                        "companion" !in names && keyword !in setOf("constructor", "get", "set") &&
                        !documented(code, i, kdocEnds)
                    ) {
                        found += "${source.lineNumberAt(i)}: ${declaredName(code, keywordAt + keyword.length)}"
                    }
                    i = keywordAt + keyword.length
                    continue
                }
                i += word.length
                continue
            }
        }
        i++
    }
    return found
}

/** The modifiers starting at [start] and the declaration keyword after them, or `null` if no declaration starts there. */
private fun modifiersAt(code: String, start: Int): Triple<List<String>, String, Int>? {
    val names = mutableListOf<String>()
    var i = start
    while (true) {
        val word = identifier.matchAt(code, i)?.value ?: return null
        if (declarationKeyword.matches(word)) {
            // `fun interface` is an interface.
            if (word == "fun") {
                val next = identifier.matchAt(code, code.skipBlanks(i + 3))
                if (next?.value == "interface") return Triple(names, "interface", next.range.first)
            }
            if (names.isEmpty() && word in setOf("get", "set", "constructor")) return null
            return Triple(names, word, i)
        }
        if (word !in modifierWords) return null
        names += word
        // Modifiers and their keyword share a line, so a word such as `value` ending a line starts nothing.
        i += word.length
        while (i < code.length && (code[i] == ' ' || code[i] == '\t')) i++
    }
}

private val modifierWords = setOf(
    "public", "private", "internal", "protected", "open", "abstract", "sealed", "final", "data", "enum", "inner",
    "value", "annotation", "fun", "const", "lateinit", "inline", "suspend", "operator", "infix", "override",
    "tailrec", "external", "companion", "expect", "actual",
)

/** Whether a KDoc ends right before the declaration at [start], with only annotations and blanks between. */
private fun documented(code: String, start: Int, kdocEnds: Set<Int>): Boolean {
    var i = start
    while (true) {
        // A KDoc is blanked like any comment, so look for its end while stepping back over blanks.
        while (i !in kdocEnds && i > 0 && code[i - 1].isWhitespace()) i--
        if (i in kdocEnds) return true
        if (i == 0) return false
        // Step back over one annotation: `@Name`, `@Name(…)` or `@target:Name(…)`.
        if (code[i - 1] == ')') {
            var depth = 0
            do {
                i--
                if (code[i] == ')') depth++ else if (code[i] == '(') depth--
            } while (depth > 0 && i > 0)
        }
        val nameEnd = i
        while (i > 0 && (code[i - 1].isJavaIdentifierPart() || code[i - 1] == '.' || code[i - 1] == ':')) i--
        if (i == nameEnd || i == 0 || code[i - 1] != '@') return false
        i--
    }
}

private fun declaredName(code: String, from: Int): String {
    // Skip type parameters and a receiver: the name is the last identifier before `(`, `:`, `=`, `{`, `<` or a line end.
    var i = code.skipBlanks(from)
    if (code.getOrNull(i) == '<') {
        var depth = 0
        do {
            if (code[i] == '<') depth++ else if (code[i] == '>') depth--
            i++
        } while (depth > 0 && i < code.length)
    }
    val rest = code.substring(i).takeWhile { it !in "(:={\n" }.trim()
    return rest.substringAfterLast('.').substringBefore('<').trim().ifEmpty { "<anonymous>" }
}

private fun String.skipBlanks(from: Int): Int {
    var i = from
    while (i < length && this[i].isWhitespace()) i++
    return i
}

private fun String.lineNumberAt(offset: Int): Int = 1 + (0 until offset).count { this[it] == '\n' }

/**
 * [source] with every comment and string or character literal replaced by spaces (newlines kept, so offsets and
 * line numbers stay), and the offsets just past each KDoc's closing `*` + `/`.
 */
private fun blankCommentsAndStrings(source: String): Pair<String, Set<Int>> {
    val out = StringBuilder(source)
    val kdocEnds = mutableSetOf<Int>()
    fun blank(from: Int, to: Int) {
        for (k in from until to) if (out[k] != '\n') out[k] = ' '
    }

    /** The offset past the string literal opening at [start] (`"` or `"""`), blanking nothing. */
    fun stringEnd(start: Int): Int {
        val raw = source.startsWith("\"\"\"", start)
        var i = start + if (raw) 3 else 1
        while (i < source.length) {
            when {
                raw && source.startsWith("\"\"\"", i) -> {
                    i += 3
                    while (i < source.length && source[i] == '"') i++
                    return i
                }
                !raw && source[i] == '\\' -> i += 2
                !raw && source[i] == '"' -> return i + 1
                source.startsWith("\${", i) -> {
                    // A template: code up to the matching brace, which may hold strings of its own.
                    var depth = 0
                    i++
                    while (i < source.length) {
                        when (source[i]) {
                            '{' -> depth++
                            '}' -> if (--depth == 0) break
                            '"' -> { i = stringEnd(i); continue }
                        }
                        i++
                    }
                    i++
                }
                else -> i++
            }
        }
        return i
    }

    var i = 0
    while (i < source.length) {
        when {
            source.startsWith("//", i) -> {
                val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                blank(i, end)
                i = end
            }
            source.startsWith("/*", i) -> {
                var depth = 0
                var j = i
                while (j < source.length) {
                    if (source.startsWith("/*", j)) { depth++; j += 2 }
                    else if (source.startsWith("*/", j)) { depth--; j += 2; if (depth == 0) break }
                    else j++
                }
                if (source.startsWith("/**", i) && !source.startsWith("/**/", i)) kdocEnds += j
                blank(i, j)
                i = j
            }
            source[i] == '"' -> {
                val end = stringEnd(i)
                blank(i, end)
                i = end
            }
            source[i] == '\'' -> {
                var j = i + 1
                while (j < source.length && source[j] != '\'') j += if (source[j] == '\\') 2 else 1
                blank(i, j + 1)
                i = j + 1
            }
            else -> i++
        }
    }
    return out.toString() to kdocEnds
}

