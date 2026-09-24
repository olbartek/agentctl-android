package io.github.olbartek.agentctl.runtime

import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * A deliberately minimal HTTP/1.1 parser: request line, headers, `Content-Length` body. One request per
 * connection (CONTRACT.md §8.1). Enough for `<cli> app …` and `curl`.
 */
public object HttpParser {
    /** The largest header block a request may have. */
    public const val MAX_HEADER_BYTES: Int = 64 * 1024

    public sealed interface Result {
        public data object Incomplete : Result
        public data object Malformed : Result
        public data class Request(val request: BridgeRequest) : Result
    }

    public fun parse(data: ByteArray, length: Int = data.size): Result {
        val headerEnd = indexOf(data, length, HEADER_END)
        if (headerEnd < 0) return if (length > MAX_HEADER_BYTES) Result.Malformed else Result.Incomplete
        val head = decodeStrictly(data, headerEnd) ?: return Result.Malformed
        val lines = head.split("\r\n")
        val requestLine = lines[0].split(" ").filter { it.isNotEmpty() }
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) return Result.Malformed

        var contentLength = 0
        for (line in lines.drop(1)) {
            val parts = line.split(":", limit = 2).filter { it.isNotEmpty() }
            if (parts.size != 2) return Result.Malformed
            if (parts[0].trim().lowercase() == "content-length") {
                val value = parts[1].trim().toIntOrNull()
                if (value == null || value < 0) return Result.Malformed
                contentLength = value
            }
        }
        val bodyStart = headerEnd + HEADER_END.size
        if (length - bodyStart < contentLength) return Result.Incomplete
        val body = String(data, bodyStart, contentLength, Charsets.UTF_8)

        val target = requestLine[1]
        var path = target
        val query = linkedMapOf<String, String>()
        try {
            val uri = URI(target)
            uri.path?.let { path = it }
            uri.rawQuery?.split("&")?.filter { it.isNotEmpty() }?.forEach { item ->
                val equals = item.indexOf('=')
                val name = if (equals < 0) item else item.substring(0, equals)
                val value = if (equals < 0) "" else item.substring(equals + 1)
                query[percentDecode(name)] = percentDecode(value)
            }
        } catch (_: URISyntaxException) {
            // Not a URI: the whole target is the path, as the reference does.
        }
        return Result.Request(BridgeRequest(requestLine[0], path, query, body))
    }

    public fun serialize(response: BridgeResponse): ByteArray {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val reason = when (response.status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val head = listOf(
            "HTTP/1.1 ${response.status} $reason",
            "Content-Type: ${response.contentType}",
            "Content-Length: ${body.size}",
            "X-Appctl-Exit: ${response.exitCode}",
            "Connection: close",
            "",
            "",
        ).joinToString("\r\n")
        return head.toByteArray(Charsets.UTF_8) + body
    }

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

    /** Percent-decoding as `URLComponents.queryItems` does it: `%XX` only; `+` stays a plus. */
    private fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val bytes = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '%' && index + 2 < text.length && text.substring(index + 1, index + 3).all { it.isHexDigit() }) {
                bytes.write(text.substring(index + 1, index + 3).toInt(16))
                index += 3
            } else {
                bytes.write(character.toString().toByteArray(Charsets.UTF_8))
                index += 1
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || lowercaseChar() in 'a'..'f'
}
