package io.github.olbartek.agentctl.runtime

import java.security.MessageDigest

/**
 * Whether the bridge answers a request at all, decided from its headers before it is routed. After a malformed request
 * or one over [HttpParser.MAX_BODY_BYTES] (400, which the parser decides), in this order:
 *
 * 1. A `Host` that does not name the loopback (`127.0.0.1` or `localhost`, in any ASCII case, with any port or none),
 *    a missing `Host`, or any `Origin`: `403 forbidden`, exit 3. This stops DNS rebinding (a foreign `Host`) and the
 *    cross-origin requests that carry an `Origin`. It does not stop every request a web page can make: a no-cors
 *    `GET` carries a loopback `Host` and no `Origin`.
 * 2. Without the expected token in `X-Appctl-Token`: `401 unauthorized`, exit 3. Compared in constant time. This
 *    covers the rest: only whoever launched the app knows the token.
 */
internal object BridgeAccess {
    /** The answer to a request that names another host or comes from a web page. */
    val FORBIDDEN: BridgeResponse = BridgeResponse(403, "forbidden\n", exitCode = RunStatus.INTERNAL_ERROR.code)

    /** The answer to a request without the launch's token. */
    val UNAUTHORIZED: BridgeResponse = BridgeResponse(401, "unauthorized\n", exitCode = RunStatus.INTERNAL_ERROR.code)

    /** The refusal [request] gets, or `null` when it may be routed. With no [token] expected, none is checked. */
    fun refusal(request: BridgeRequest, token: String?): BridgeResponse? {
        val host = request.headers["host"]
        if (host == null || !isLoopbackHost(host) || "origin" in request.headers) return FORBIDDEN
        if (token != null && !tokenMatches(token, request.headers[TOKEN])) return UNAUTHORIZED
        return null
    }

    /** `127.0.0.1` or `localhost` (ASCII case-insensitive), then nothing or `:` and a port's digits. */
    fun isLoopbackHost(value: String): Boolean {
        val colon = value.lastIndexOf(':')
        val name = if (colon < 0) value else value.substring(0, colon)
        val port = if (colon < 0) "" else value.substring(colon + 1)
        if (!port.all { it in '0'..'9' }) return false
        return name == "127.0.0.1" || HttpParser.asciiLowercase(name) == "localhost"
    }

    /** Whether [given] is [expected], compared in constant time: how long it takes says nothing of how much matched. */
    fun tokenMatches(expected: String, given: String?): Boolean =
        given != null && MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), given.toByteArray(Charsets.UTF_8))

    private val TOKEN = HttpParser.asciiLowercase(HttpParser.TOKEN_HEADER)
}
