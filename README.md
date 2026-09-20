# agentctl-android

AgentCtl is a toolkit that makes an app "agent-addressable": every screen describes itself with a path, a compact key=value summary, an error code, and a list of commands. A coding agent can drive the app from the command line — headlessly, with no simulator — and assert on what it sees.

This repository will hold the Kotlin implementation of AgentCtl for Android apps.

The cross-platform contract (script language, step output format, `expect` semantics, exit codes, determinism requirements) is specified in [CONTRACT.md](https://github.com/olbartek/agentctl-ios/blob/main/CONTRACT.md) in the [agentctl-ios](https://github.com/olbartek/agentctl-ios) repository. This implementation targets **Contract v1**.

Nothing is implemented yet.
