# agentctl-android

The Kotlin port of [agentctl-ios](https://github.com/olbartek/agentctl-ios) (locally
`~/Developer/Projects/bartosz/agentctl-ios`). AgentCtl makes an app agent-addressable: every screen describes itself
(path, summary, error, commands), and a CLI drives it headlessly on the JVM, or in the running app through a
debug-only bridge. Kotlin 2.4, kotlinx.coroutines, Gradle Kotlin DSL with a version catalog, AGP 9 for the Android
modules.

## Standing rules

1. **CONTRACT.md is the spec, and it is the iOS repo's.** It is copied here unchanged. Never edit it here; a
   change goes to agentctl-ios first and is copied back. Every behaviour it specifies must match the Swift
   reference byte for byte (`ContractExamplesTest` checks every example). When the Swift package changes, port the
   change.
2. **Headless runs are deterministic.** Nothing in the engine reads real time, randomness or the system locale;
   the only real-time reads are settling's safety limits. A new source of nondeterminism is a bug.
3. **The public API is explicit** (`explicitApi()` in every library module): every public declaration says so and
   has a KDoc.
4. **Keep the two ports parallel.** Name things as the Swift package does (`ScriptRunner`, `HeadlessHost`,
   `AppCtlConfig`, `AgentCoverage`…) unless Kotlin makes that wrong, and record any deliberate difference in the
   README's contract section or the plan below.

## Layout

```
.agent/                 AGENTS.md (this file; CLAUDE.md, AGENTS.md, GEMINI.md link here), thoughts/plans/ (design notes)
agentctl-core/          vocabulary, script parser, expect, step format + JSON, renderers, mocking, clock, Store, AgentEnvironment
agentctl-runtime/       VirtualTimeDispatcher, HeadlessHost/LiveHost, settling, ScriptRunner, scenarios, AppCtlConfig,
                        BridgeRouter/BridgeServer/HttpParser, AgentLaunchSession (the bridge minus Android)
agentctl-cli/           AgentCtl.main/run (Clikt core), subcommands, Ladder, AppLauncher (adb), BridgeClient, Snapshots
agentctl-bridge/        Android library: AgentLaunch (intent extras → session on Dispatchers.Main.immediate)
agentctl-test-support/  AgentCoverage, NoScenariosFound
examples/tinyapp/       TinyApp (JVM): the fixture; scenarios/, agent-commands.md (generated)
examples/tinyctl/       TinyApp's CLI (application plugin); ./tinyctl at the root is its wrapper (Templates/appctl)
examples/tinyapp-android/  TinyApp as an Android app with the bridge (debug) and without it (release)
tests/                  the suite, driven against TinyApp (like AgentCtlTests in Swift)
build-logic/            convention plugins: agentctl.jvm.library, agentctl.published
Templates/appctl        the wrapper hosts copy
```

## Commands

The machine has no system JDK; export it per command (never edit shell profiles):

```bash
export JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home
./gradlew build                 # everything, including the suite; what CI runs, with the two below
./tinyctl test                  # the scenarios through the real CLI
./tinyctl docs --check          # the generated command reference is current (./tinyctl docs rewrites it)
./tinyctl check                 # the ladder; --ui --device nzoz_pixel7_api36 adds the emulator (boots the AVD)
```

`local.properties` (`sdk.dir`) is gitignored. The emulator AVD `nzoz_pixel7_api36` is shared with nzozopole-android.

## Releases

Tag `vX.Y.Z` on `main` and push the tag; JitPack builds it (`jitpack.yml`) as
`com.github.olbartek.agentctl-android:<module>:vX.Y.Z`. Bump `agentctlVersion` in `gradle.properties` with it.

## Git workflow

- Branch per change, PR to `main`, squash-merge. Commit only after `./gradlew build` passes.
- `gh`'s active account may be another one; use `GH_TOKEN=$(gh auth token --user olbartek)` per command.
