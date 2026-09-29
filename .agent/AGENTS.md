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
   README's contract section.
5. **Two ports, one product: every change lands in both repositories.** The same features, the same CLI commands
   and options, the same example apps (TinyApp, AgentShop) with byte-identical scenario files (edit them in
   agentctl-ios, copy them here), and the same MAJOR.MINOR version (see Releases). A fix found here is checked
   against the Swift package too.

## Layout

```
.agent/                 AGENTS.md (this file; CLAUDE.md, AGENTS.md, GEMINI.md link here); thoughts/ (local notes, gitignored)
agentctl-core/          vocabulary, script parser, expect, step format + JSON, renderers, mocking, clock, Store, AgentEnvironment
agentctl-runtime/       VirtualTimeDispatcher, HeadlessHost/LiveHost, settling, ScriptRunner, scenarios, AppCtlConfig,
                        BridgeRouter/BridgeServer/HttpParser, AgentLaunchSession (the bridge minus Android)
agentctl-cli/           AgentCtl.main/run (Clikt core), subcommands, Ladder, AppLauncher (adb), BridgeClient, Snapshots
agentctl-bridge/        Android library: AgentLaunch (intent extras → session on Dispatchers.Main.immediate)
agentctl-test-support/  AgentCoverage, NoScenariosFound
examples/tinyapp/       TinyApp (JVM): the fixture; scenarios/, agent-commands.md (generated)
examples/tinyctl/       TinyApp's CLI (application plugin); ./tinyctl at the root is its wrapper (Templates/appctl)
examples/tinyapp-config/   TinyApp's AppCtlConfig, apart from the app so its release build leaves agentctl-runtime out
examples/tinyapp-android/  TinyApp as an Android app with the bridge (debug) and without it (release)
examples/agentshop/     the showcase (iOS's AgentShop): shop/ ctl/ shopctl/ app/ (Compose), scenarios/, bench/, appctl
tests/                  the suite, driven against TinyApp (like AgentCtlTests in Swift)
build-logic/            convention plugins: agentctl.jvm.library, agentctl.published, agentctl.release.check
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

The two repositories share MAJOR.MINOR; PATCH is each repository's own.

- **MAJOR.MINOR is the lockstep promise:** the same CONTRACT.md, features, CLI commands and options, and scenario
  files. A minor or major release ships in both repositories together, even when one side changes nothing but its
  version.
- **PATCH is per repository,** for a fix no script or CLI user can see: a platform bug, a flaky test, a build
  problem. Android at 0.4.2 beside iOS at 0.4.1 is the same 0.4 product with one more fix on Android.
- **Every fix is checked against the other port.** Its PR says "ported to <repo>#<n>" or "not applicable: <why>".
  Each side whose shipped code changes bumps its own PATCH; a side that changes only tests or docs does not release.
- **A change to step output, the CLI's surface, CONTRACT.md or a scenario is never a patch.** It is a minor release
  of both repositories.

Tags are bare `X.Y.Z` on `main`. (Tags before 0.4.1 were `vX.Y.Z`.) To release here: bump
`agentctlVersion` in `gradle.properties` and the README's pins and Status, merge, then tag and push the tag; JitPack
builds it (`jitpack.yml`) as `com.github.olbartek.agentctl-android:<module>:X.Y.Z`. For a minor or major release, tag
agentctl-ios with the same number.

## Git workflow

- Branch per change, PR to `main`, squash-merge. Commit only after `./gradlew build` passes.
- `gh`'s active account may be another one; use `GH_TOKEN=$(gh auth token --user olbartek)` per command.
