# Plan: the Kotlin port of AgentCtl (contract v1)

Date: 2026-09-24. Source: `olbartek/agentctl-ios` at its current `main` (version 0.3), whose
`CONTRACT.md` is copied here unchanged. The goal is the same library for Android and Kotlin: the
same scripts print the same bytes, and a host integrates it the same way.

## Brainstorm: what has to change, and what must not

**Must not change (the contract).** The following reproduce the Swift reference byte for byte:

- the script language and parse errors (§1);
- the three runtime commands and their messages (§2);
- the step text format (§3);
- `expect` (§4);
- exit codes (§5);
- the headless determinism rules (§6);
- the bridge wire protocol (§8).

The golden tests take every example in `CONTRACT.md`, run it against the Kotlin TinyApp, and
compare the output.

**What has no Kotlin equivalent, and what replaces it.**

| Swift piece | Problem on Android | Kotlin decision |
|---|---|---|
| TCA `Store`/`Reducer`/`Effect` | Android has no single architecture to target | A small unidirectional store in `agentctl-core`: `Store`, `Reducer`, `Effect` (run, cancellable, send, cancel, map). Apps with their own architecture implement `AgentStore` (state, send, effects in flight). |
| `@Dependency` overrides | Kotlin has no ambient dependency system | An explicit `AgentEnvironment` handed to the store factory: `scope`, `clock`, `mocks`, `uuids`, `random`, `zone`, `locale`, `now`. Headless pins every field; live uses system values. |
| `TestClock` + main serial executor | — | `VirtualTimeDispatcher`: a single-threaded `CoroutineDispatcher` + `Delay` with a virtual clock. Tasks run one at a time in `(time, sequence)` order, so settling can stop a restless app at its real-time limit (kotlinx-coroutines-test's `runCurrent` would spin forever on one). |
| `CountingClock.activeSleeps` | — | `CountingClock`: `sleep()` counts itself while suspended; `pending` = active sleeps, as in Swift. |
| `mockCall` via dependencies | — | `MockBackend.call(name, error) { … }`, where `MockBackend` holds the call log, faults, latency, clock and random. |
| `customDump` for `state` and `--diff` | — | `StateDump`: an indented rendering of `toString()` (data classes), and a line diff. The contract leaves both informational. |
| swift-argument-parser | — | Clikt 5. Usage errors map to exit 2; help goes to stdout with exit 0. |
| `xcodebuild`/`simctl`/SwiftPM ladder | — | Gradle tasks and `adb`. L0: the config's build tasks. L1: its test tasks. L2: scenarios in-process. docs. L3: the Roborazzi verify/record tasks. L4: install, launch seeded, run a scenario over the bridge, screencap. |
| Launch arguments (`-agent-port …`) | An Android app has no argv | The same names as intent extras without the leading dash: `agent-port` (int), `appctl-seed` (string), `mock-latency` (int), `clear-session` (bool). The CLI sends them with `am start`. Documented as the Android transport of §8.2. |
| `NWListener` on 127.0.0.1 | — | A `java.net.ServerSocket` bound to loopback in `agentctl-runtime`, so it is unit-testable on the JVM. The CLI reaches the device through `adb forward tcp:<port> tcp:<port>`. |
| `#if DEBUG` bridge | — | `agentctl-bridge` is an Android library added with `debugImplementation` only. The host keeps its `AgentLaunch` call in `src/debug`, with a no-op twin in `src/release`. |
| `Templates/appctl` (`swift build`) | — | The same wrapper around `./gradlew -q :<module>:installDist`; it execs `build/install/<name>/bin/<name>` with `APPCTL_ROOT` set. |

**Choices a port must make (from the contract's Open Questions).** The Kotlin port copies the
reference in each case, so fixtures are shared:

- escapes are generic;
- a duration takes a single unit;
- a `"` inside a value is not escaped;
- the settle thresholds match: 3 stable rounds with a 2 s limit headless; 250 ms quiet, a 3 s
  limit and 20 ms polling live;
- the JSON summary is a map with sorted keys, pretty-printed like Foundation's `JSONEncoder`
  (`"key" : value`);
- the "Valid here" tail is `expect, advance, mock`.

A script is iterated by **grapheme cluster**, not by `Char`, because that is what a Swift
`Character` is; column numbers then agree for any input. Whitespace means Unicode `White_Space`.

## Modules

| Module | Kind | Swift counterpart | Contents |
|---|---|---|---|
| `agentctl-core` | JVM | `AgentCtlCore` | vocabulary, parser, `expect`, step format/JSON, docs/screens renderers, mocking, clock, `Store`, `AgentEnvironment` |
| `agentctl-runtime` | JVM | `AgentCtlTCA` + the bridge's transport | `VirtualTimeDispatcher`, `HeadlessHost`, `LiveHost`, settling, `ScriptRunner`, `ScenarioRunner`, `AppCtlConfig`, `BridgeRouter`, `BridgeServer`/HTTP, `AgentLaunchOptions`/seed |
| `agentctl-cli` | JVM | `AgentCtlCLI` | `AgentCtl.run(config, args)`, the subcommands, `Ladder`, `Layout`, `Adb`, snapshots, `BridgeClient` |
| `agentctl-bridge` | Android library | `AgentCtlBridge` | `AgentLaunch`: reads the intent, builds the live host on the main thread, applies the seed, starts the server |
| `agentctl-test-support` | JVM | `AgentCtlTestSupport` | `AgentCoverage` and `NoScenariosFound` |
| `examples:tinyapp` | JVM | `TinyApp` | the same two screens, one client and three scenarios, plus `agent-commands.md` |
| `examples:tinyctl` | JVM app | `tinyctl` | `main` → `AgentCtl.main` |
| `examples:tinyapp-android` | Android app | (none) | TinyApp with plain views; the bridge in `src/debug`, none in `src/release` |
| `tests` | JVM tests | `AgentCtlTests` | the suite, including `CONTRACT.md` golden tests and a live-versus-headless bridge check |

## Steps

1. Gradle build: a version catalog, Kotlin 2.4, JVM target 17 and the wrapper (Gradle 9.7).
   Android modules use AGP 9.4 (built-in Kotlin), compileSdk 36 and minSdk 28.
2. `agentctl-core`, with unit tests: parser, `ArgumentText`, `parseDuration`, `Expectation`,
   `StepFormatter` (text and JSON), renderers, mocks.
3. `agentctl-runtime`: the dispatcher, hosts, settling, runner, scenarios, config, router and
   server.
4. TinyApp and `tinyctl`. Generate `agent-commands.md` and compare it by hand with the Swift one:
   they differ only where the invocation and the `+Agent.kt` wording differ.
5. `agentctl-cli`: `run`/`state`/`screens`/`docs`/`test`/`snapshots`/`check`/`app …`.
6. `agentctl-test-support`.
7. `tests`:
   - the `CONTRACT.md` golden examples;
   - executor, parser, expectation, settle (restless), headless pins, bridge router, bridge
     server live versus headless, launch options, coverage, help pages, layout, root marker;
   - running the scenarios ten times gives identical output.
8. `agentctl-bridge`, plus `examples:tinyapp-android`, so the bridge runs for real: `app launch`, `app run`
   and `check --ui` on the emulator (`nzoz_pixel7_api36`).
9. The README (the iOS one, translated), `Templates/appctl`, a `./tinyctl` wrapper for the
   example, CI (`./gradlew build`, `./tinyctl test`, `./tinyctl docs --check`), and JitPack
   publishing (`maven-publish`, `jitpack.yml`).
10. PR to `main`, squash-merge, tag `v0.1.0`.

## Outcome (2026-09-24)

- Everything above is done. The sample app moved into scope: without it, nothing would have exercised the Android
  bridge.
- `ContractExamplesTest` reproduces all eight `run`/`test` examples and both HTTP examples of CONTRACT.md byte for
  byte. `./tinyctl screens` matches the reference README's listing. `agent-commands.md` differs from the Swift one
  only in its generated-by line.
- On the emulator:
  - `check --ui` passes L0–L4.
  - `app run` output matches headless output for scripts without timers.
- Found on the device: an activity recreated right after a cold boot cancelled a seed that the activity's own scope
  had started. The fix: start `AgentLaunch` from a process-wide scope, as documented on `AgentLaunch` and in the
  README.
- Found in L4: the bridge answers before the first screen's own fetch finishes. The fix: L4 launches with zero mock
  latency.

## Still open

- Screenshot tests (L3) in the sample app: TinyApp has none, as on iOS.
- A Compose sample; the plain-views sample keeps the dependency list minimal.
