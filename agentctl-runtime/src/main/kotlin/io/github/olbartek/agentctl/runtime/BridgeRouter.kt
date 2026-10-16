package io.github.olbartek.agentctl.runtime

import io.github.olbartek.agentctl.StepFormatter

/** An HTTP request as the agent bridge sees it. */
public data class BridgeRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: String = "",
    /**
     * The request's headers, by name in ASCII lowercase (`host`, `x-appctl-token`), each value trimmed; a header sent
     * more than once has its values joined with `, `. Empty for a request made in code.
     */
    val headers: Map<String, String> = emptyMap(),
) {
    /** A request without headers: the constructor before 0.9, kept so code built against it still links. */
    public constructor(method: String, path: String, query: Map<String, String> = emptyMap(), body: String = "") :
        this(method, path, query, body, emptyMap())
}

/** An answer of the agent bridge: an HTTP status, a body and its content type, and the exit code the CLI exits with. */
public data class BridgeResponse(
    val status: Int,
    val body: String,
    val contentType: String = "text/plain; charset=utf-8",
    /** The `appctl` exit code for this response, sent as `X-Appctl-Exit`. */
    val exitCode: Int,
)

/**
 * The agent bridge's endpoints (CONTRACT.md §8), independent of the transport so they can be tested on the JVM:
 *
 * - `POST /run` (body: a script; `?format=json` for JSON): the same output as the CLI's `run`, with what `run`
 *   prints to stderr — a script's parse error — in the body too: after the steps as `error: …` in the text form,
 *   and as `{"error": …, "steps": […]}` in place of the steps array in the JSON form.
 * - `GET /state`: the root state ([StateDump]).
 * - `GET /screens`: every screen and its commands.
 * - `GET /snapshot`: the current screen's summary line.
 *
 * Call [handle] on the app's own thread.
 */
public class BridgeRouter<A>(
    private val runner: ScriptRunner<A>,
    private val screensText: () -> String,
) {
    /** Answers one request. An unknown path is a 404 and a wrong method a 405, both with exit code 2. */
    public suspend fun handle(request: BridgeRequest): BridgeResponse {
        val known = request.path in setOf("/run", "/state", "/screens", "/snapshot")
        return when {
            request.method == "POST" && request.path == "/run" -> {
                val result = runner.run(request.body)
                if (request.query["format"] == "json") {
                    BridgeResponse(200, StepFormatter.json(result.steps, result.message), "application/json", result.status.code)
                } else {
                    var body = StepFormatter.text(result.steps)
                    result.message?.let { body += (if (body.isEmpty()) "" else "\n") + "error: $it" }
                    BridgeResponse(200, body + "\n", exitCode = result.status.code)
                }
            }
            request.method == "GET" && request.path == "/state" -> BridgeResponse(200, runner.stateDump + "\n", exitCode = 0)
            request.method == "GET" && request.path == "/screens" -> BridgeResponse(200, screensText() + "\n", exitCode = 0)
            request.method == "GET" && request.path == "/snapshot" ->
                BridgeResponse(200, StepFormatter.text(runner.snapshot("(snapshot)")) + "\n", exitCode = 0)
            known -> BridgeResponse(405, "method not allowed\n", exitCode = RunStatus.USAGE.code)
            else -> BridgeResponse(
                404,
                "not found; endpoints: POST /run, GET /state, GET /screens, GET /snapshot\n",
                exitCode = RunStatus.USAGE.code,
            )
        }
    }
}
