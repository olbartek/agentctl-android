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
   `AppCtlConfig`, `AgentCoverage`…) unless Kotlin makes that wrong, and record any deliberate difference in
   docs/guides/platform-differences.md.
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
scripts/wiki-sync       builds the GitHub wiki from README.md and docs/guides (agentctl-ios's copy, byte for byte)
```

## Commands

The machine has no system JDK; export it per command (never edit shell profiles):

```bash
export JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home
./gradlew build                 # everything, including the suite; what CI runs, with the two below
./tinyctl test                  # the scenarios through the real CLI
./tinyctl docs --check          # the generated command reference is current (./tinyctl docs rewrites it)
./tinyctl check                 # the ladder; --ui --device agentctl_pixel7_api36 adds the emulator (boots the AVD)
```

`local.properties` (`sdk.dir`) is gitignored. This repository's emulator AVD is `agentctl_pixel7_api36`; never use
`nzoz_pixel7_api36`, which belongs to nzozopole-android. Shut the emulator down when no live run is pending.

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
`agentctlVersion` in `gradle.properties` and the version pins (README.md, docs/guides/integration.md and
docs/guides/overview.md), add the version's section to docs/guides/releases.md (`## X.Y.Z (YYYY-MM-DD)`, newest
first, with its "Moving a host to X.Y"), merge, then tag and push the tag, run `scripts/wiki-sync` and check it with
`scripts/wiki-sync --check`; JitPack builds the tag (`jitpack.yml`) as
`com.github.olbartek.agentctl-android:<module>:X.Y.Z`. For a minor or major release, tag agentctl-ios with the same
number.

## The wiki

The [wiki](https://github.com/olbartek/agentctl-android/wiki) is built from README.md and docs/guides by
`scripts/wiki-sync`; it is never edited in the browser.

Every change that touches public API, CONTRACT, CLI, docs/guides, README or a release updates the wiki in the same
cycle: after the merge, run `scripts/wiki-sync` and push the wiki; a release is not done until
`scripts/wiki-sync --check` passes. The page list and format are shared with the sibling library
(nzozopole-hub plans/2026-10-31-agentctl-wiki.md); changing them is agreed through nzoz-lead and done on both.

- A wiki page's source is one file: Home is README.md's intro and docs/guides/overview.md; every other page is the
  docs/guides file `scripts/wiki-sync`'s page table names. Write in those files, never in the wiki.
- `scripts/wiki-sync --out <dir>` writes the pages without publishing, for a look first.
- `scripts/wiki-sync` is the same file in both repositories: change it in agentctl-ios and copy it here.

## Git workflow

- Branch per change, PR to `main`, squash-merge. Commit only after `./gradlew build` passes.
- `gh`'s active account may be another one; use `GH_TOKEN=$(gh auth token --user olbartek)` per command.
- After every merged branch: remove its worktree, delete the local and remote branch, and delete that worktree's
  build caches (Xcode DerivedData / Gradle build dirs). Never remove a worktree with uncommitted or unpushed changes,
  or one another session holds.
  - Here: `git worktree remove` (it takes the worktree's `build/` and `.gradle/` with it), `git branch -D` the
    squash-merged branch, and `git push origin --delete` it when no open PR needs it. Scratchpad worktrees of this
    repo count too.
  - The main checkout and its caches stay, and so does the shared `~/.gradle` cache.
