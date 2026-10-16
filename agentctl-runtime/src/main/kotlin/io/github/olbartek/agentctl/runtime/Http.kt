package io.github.olbartek.agentctl.runtime

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * A deliberately minimal HTTP/1.1 parser: request line, headers, `Content-Length` body. One request per
 * connection (CONTRACT.md §8.1). Enough for `<cli> app …` and `curl`.
 */
public object HttpParser {
    /** The header naming the app a bridge belongs to (CONTRACT.md §8.4). */
    public const val APP_HEADER: String = "X-Appctl-App"

    /** The bridge's platform (`android`, `ios`) on every response, beside [APP_HEADER] (CONTRACT.md §8.4). */
    public const val PLATFORM_HEADER: String = "X-Appctl-Platform"

    /** The header every request carries the launch's token in (the `agent-token` launch argument). */
    public const val TOKEN_HEADER: String = "X-Appctl-Token"

    /** The largest header block a request may have, with the blank line that ends it (CONTRACT.md §8.1). */
    public const val MAX_HEADER_BYTES: Int = 64 * 1024

    /**
     * The largest body a request may have, 1 MiB: a request whose `Content-Length` is larger is malformed, decided
     * from its headers alone, before any of the body is read.
     */
    public const val MAX_BODY_BYTES: Int = 1024 * 1024

    /** What [parse] makes of the bytes received so far. */
    public sealed interface Result {
        /** Not a whole request yet: read more and parse again. */
        public data object Incomplete : Result

        /** Not a request this parser accepts; reading more will not help. */
        public data object Malformed : Result

        /** A whole request. */
        public data class Request(val request: BridgeRequest) : Result
    }

    /** Parses the first [length] bytes of [data]. */
    public fun parse(data: ByteArray, length: Int = data.size): Result {
        val head = when (val parsed = parseHead(data, length)) {
            Head.Incomplete -> return Result.Incomplete
            Head.Malformed -> return Result.Malformed
            is Head.Complete -> parsed
        }
        if (length - head.bodyStart < head.contentLength) return Result.Incomplete
        val body = String(data, head.bodyStart, head.contentLength, Charsets.UTF_8)
        return Result.Request(head.request.copy(body = body))
    }

    /** What [parseHead] makes of the bytes received so far: whether the request line and headers are all there. */
    internal sealed interface Head {
        /** The header block has not ended yet. */
        data object Incomplete : Head

        /**
         * Not a request this parser accepts: one whose body would be over [MAX_BODY_BYTES], with any
         * `Transfer-Encoding`, more than one `Content-Length` or one that is not ASCII digits, a folded header line, or
         * whitespace before a header's colon.
         */
        data object Malformed : Head

        /** The request without its body ([request]'s is empty), which starts at [bodyStart] and has [contentLength]. */
        data class Complete(val request: BridgeRequest, val bodyStart: Int, val contentLength: Int) : Head
    }

    /**
     * The request line and headers in the first [length] bytes of [data], as soon as they are all there: the bridge
     * decides whether to answer a request from them, before it reads the body.
     */
    internal fun parseHead(data: ByteArray, length: Int): Head {
        val headerEnd = indexOf(data, length, HEADER_END)
        if (headerEnd < 0) return if (length > MAX_HEADER_BYTES) Head.Malformed else Head.Incomplete
        // However it arrived: the header block, with the blank line that ends it, holds at most MAX_HEADER_BYTES.
        if (headerEnd + HEADER_END.size > MAX_HEADER_BYTES) return Head.Malformed
        val head = decodeStrictly(data, headerEnd) ?: return Head.Malformed
        val lines = head.split("\r\n")
        val requestLine = lines[0].split(" ").filter { it.isNotEmpty() }
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) return Head.Malformed

        var contentLength: Int? = null
        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            // A line folded onto the one before it (obs-fold) is not accepted.
            if (line.startsWith(" ") || line.startsWith("\t")) return Head.Malformed
            val parts = line.split(":", limit = 2).filter { it.isNotEmpty() }
            if (parts.size != 2) return Head.Malformed
            // Nor is whitespace between a header's name and its colon.
            if (parts[0] != parts[0].trimSwiftWhitespaces()) return Head.Malformed
            val name = asciiLowercase(parts[0])
            val value = parts[1].trimSwiftWhitespaces()
            when (name) {
                // The body is framed by one Content-Length alone: no chunking, whatever the encoding named.
                "transfer-encoding" -> return Head.Malformed
                "content-length" -> {
                    if (contentLength != null) return Head.Malformed
                    contentLength = contentLength(parts[1]) ?: return Head.Malformed
                }
            }
            // A repeated header is one, its values joined as HTTP joins them (RFC 9110 §5.3).
            headers[name] = headers[name]?.let { "$it, $value" } ?: value
        }
        val target = requestLine[1]
        val (path, query) = RequestTarget.parse(target) ?: (target to emptyMap())
        return Head.Complete(BridgeRequest(requestLine[0], path, query, "", headers), headerEnd + HEADER_END.size, contentLength ?: 0)
    }

    /**
     * A `Content-Length` value: one or more ASCII digits between optional spaces and tabs, at most [MAX_BODY_BYTES];
     * `null` for anything else (a sign, other whitespace, other digits, too large).
     */
    private fun contentLength(raw: String): Int? {
        val value = raw.trim { it == ' ' || it == '\t' }
        if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
        val digits = value.trimStart('0')
        if (digits.length > 7) return null
        val number = if (digits.isEmpty()) 0 else digits.toInt()
        return number.takeIf { it <= MAX_BODY_BYTES }
    }

    /** [text] with ASCII `A`–`Z` lowercased and nothing else changed: header names, host names. */
    internal fun asciiLowercase(text: String): String =
        if (text.none { it in 'A'..'Z' }) text else String(CharArray(text.length) { text[it].let { c -> if (c in 'A'..'Z') c + 32 else c } })

    /**
     * The response on the wire. [appId] — the app's application id — goes out as `X-Appctl-App` on every response, and
     * [platform] as `X-Appctl-Platform`, so the CLI can tell which app, and which build of it, a bridge belongs to
     * (CONTRACT.md §8.4); `null` leaves a header out.
     */
    @JvmOverloads
    public fun serialize(response: BridgeResponse, appId: String? = null, platform: String? = null): ByteArray {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val reason = when (response.status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val head = (listOf(
            "HTTP/1.1 ${response.status} $reason",
            "Content-Type: ${response.contentType}",
            "Content-Length: ${body.size}",
            "X-Appctl-Exit: ${response.exitCode}",
        ) + listOfNotNull(appId?.let { "$APP_HEADER: $it" }, platform?.let { "$PLATFORM_HEADER: $it" }) + listOf(
            "Connection: close",
            "",
            "",
        )).joinToString("\r\n")
        return head.toByteArray(Charsets.UTF_8) + body
    }

    /** `trimmingCharacters(in: .whitespaces)`: Foundation's whitespaces are the space separators, a tab and U+200B. */
    private fun String.trimSwiftWhitespaces(): String =
        trim { it == '\t' || it == '\u200B' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

    private val HEADER_END = "\r\n\r\n".toByteArray(Charsets.US_ASCII)

    private fun indexOf(data: ByteArray, length: Int, needle: ByteArray): Int {
        outer@ for (start in 0..length - needle.size) {
            for (offset in needle.indices) if (data[start + offset] != needle[offset]) continue@outer
            return start
        }
        return -1
    }

    private fun decodeStrictly(data: ByteArray, length: Int): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data, 0, length))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

/**
 * A request target read as the reference reads it: `URLComponents(string: target)`, then `.path` and `.queryItems`.
 * Foundation encodes the characters a URL may not hold rather than refusing them, one component at a time, so a
 * component (the path, the query) holding one of them, or a `%` that starts no escape, is taken as sent; any other
 * component is percent-decoded, and an escape that decodes to invalid UTF-8 gives "". Items split on `&`, a name from
 * its value on the first `=`; an item without one has the value "", and a repeated name keeps the last. `null` where
 * `URLComponents` gives nil (a scheme that is no scheme, a bad port or host), and the caller then takes the whole
 * target as the path, as the reference does.
 */
internal object RequestTarget {
    fun parse(target: String): Pair<String, Map<String, String>>? {
        val hash = target.indexOf('#')
        val beforeFragment = if (hash < 0) target else target.substring(0, hash)
        val mark = beforeFragment.indexOf('?')
        var rest = if (mark < 0) beforeFragment else beforeFragment.substring(0, mark)
        val rawQuery = if (mark < 0) null else beforeFragment.substring(mark + 1)

        // A scheme: the text before the first ':', when no '/' comes first. Foundation refuses one that is no scheme,
        // unless it is empty or holds '@', '[' or ']': then the colon is the path's.
        val colon = rest.indexOf(':')
        val slash = rest.indexOf('/')
        if (colon > 0 && (slash < 0 || colon < slash)) {
            val scheme = rest.substring(0, colon)
            if (isScheme(scheme)) {
                rest = rest.substring(colon + 1)
            } else if (scheme.none { it in "@[]" }) {
                return null
            }
        }
        if (rest.startsWith("//")) {
            val authorityEnd = rest.indexOf('/', 2).let { if (it < 0) rest.length else it }
            if (!isAuthority(rest.substring(2, authorityEnd))) return null
            rest = rest.substring(authorityEnd)
        }

        val path = component(rest) ?: ""
        val query = linkedMapOf<String, String>()
        if (!rawQuery.isNullOrEmpty()) {
            val literal = !isValid(rawQuery, QUERY_EXTRA)
            for (item in rawQuery.split('&')) {
                val equals = item.indexOf('=')
                val name = if (equals < 0) item else item.substring(0, equals)
                val value = if (equals < 0) "" else item.substring(equals + 1)
                query[if (literal) name else percentDecode(name) ?: ""] = if (literal) value else percentDecode(value) ?: ""
            }
        }
        return path to query
    }

    /** A path as `URLComponents.path` gives it: as sent when Foundation had to encode it, else decoded. */
    private fun component(text: String): String? = if (isValid(text, PATH_EXTRA)) percentDecode(text) else text

    private const val UNRESERVED_EXTRA = "-._~"
    private const val SUB_DELIMS = "!$&'()*+,;="
    private const val PATH_EXTRA = "$UNRESERVED_EXTRA$SUB_DELIMS:@/"
    private const val QUERY_EXTRA = "$PATH_EXTRA?"

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** Whether [text] holds only the characters [extra] and ASCII letters and digits allow, and only whole escapes. */
    private fun isValid(text: String, extra: String): Boolean {
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '%') {
                if (index + 2 >= text.length) return false
                if (!text[index + 1].isHexDigit() || !text[index + 2].isHexDigit()) return false
                index += 3
            } else {
                if (!character.isAsciiLetterOrDigit() && character !in extra) return false
                index += 1
            }
        }
        return true
    }

    /** RFC 3986: a letter, then letters, digits, `+`, `-` and `.`. */
    private fun isScheme(text: String): Boolean =
        (text[0] in 'a'..'z' || text[0] in 'A'..'Z') && text.all { it.isAsciiLetterOrDigit() || it in "+-." }

    /** `[userinfo@]host[:port]`: Foundation encodes a user's characters, but refuses a bad host or port. */
    private fun isAuthority(authority: String): Boolean {
        val hostPort = authority.substring(authority.lastIndexOf('@') + 1)
        val port: String
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return false
            // An IP literal; after a '%' comes a zone, which Foundation encodes rather than checks.
            if (!hostPort.substring(1, close).substringBefore('%').all { it.isAsciiLetterOrDigit() || it in "$UNRESERVED_EXTRA$SUB_DELIMS:" }) {
                return false
            }
            val after = hostPort.substring(close + 1)
            if (after.isNotEmpty() && !after.startsWith(":")) return false
            port = after.removePrefix(":")
        } else {
            val colon = hostPort.indexOf(':')
            val host = if (colon < 0) hostPort else hostPort.substring(0, colon)
            port = if (colon < 0) "" else hostPort.substring(colon + 1)
            if (!isValid(host.filter { it.code < 0x80 }, "$UNRESERVED_EXTRA$SUB_DELIMS")) return false
            if (host.any { it.code >= 0x80 } && !isIdnaHost(host)) return false
        }
        return port.all { it in '0'..'9' }
    }

    /**
     * The label rules Foundation's IDNA (UTS 46) applies to a host that is not ASCII: no empty label but the last, none
     * that starts or ends with `-`, and none with `--` in its third and fourth places. The rest of UTS 46 (combining
     * marks, joiners, mixed scripts) is not checked: the bridge's clients send origin-form targets, which have no host.
     */
    private fun isIdnaHost(host: String): Boolean {
        val labels = host.split('.', '\u3002', '\uFF0E', '\uFF61')
        return labels.withIndex().all { (index, label) ->
            if (label.isEmpty()) index == labels.lastIndex && index > 0
            else !label.startsWith("-") && !label.endsWith("-") && !(label.length >= 4 && label.substring(2, 4) == "--")
        }
    }

    /** `%XX` escapes to bytes, read as UTF-8; `null` when they are not UTF-8. Only called on valid components. */
    private fun percentDecode(text: String): String? {
        if ('%' !in text) return text
        val bytes = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '%') {
                bytes.write(text.substring(index + 1, index + 3).toInt(16))
                index += 3
            } else {
                bytes.write(character.toString().toByteArray(Charsets.UTF_8))
                index += 1
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
