# Releases

Tags are bare `X.Y.Z`; before 0.4.1 this port had its own numbers and `v`-prefixed tags (v0.1.0–v0.2.0). From
0.5.0, each release's full notes are on [GitHub](https://github.com/olbartek/agentctl-android/releases); JitPack serves
every tag as `com.github.olbartek.agentctl-android:<module>:<tag>`. This port and agentctl-ios share MAJOR.MINOR,
released together; a PATCH is one repository's own fix, so the two patch numbers can differ.

## 0.12.1 (2026-11-01)

An Android-only patch on 0.12.0: `app test` and `check --ui` no longer leave their `adb forward` behind. Each scenario's
launch (and L4's) removes the forward it made once it has run, so the next run of the same app, from any checkout,
finds that port free instead of moving on to 8766; no other forward is touched. `app launch` keeps its forward, which
`app run` needs, and the next launch removes it as before. So `app run` right after `app test` or `check --ui` needs an
`app launch` first, where agentctl-ios's still reaches the app the last scenario left running
([the platform differences](platform-differences.md)). Its message is unchanged and says the port from
`bridge.json` is stale once the app has quit; after `app test` the app has not quit, but the advice holds: run
`app launch` again. The contract, CLI and scenario files are as in 0.12.0; agentctl-ios stayed at 0.12.0. [Release notes](https://github.com/olbartek/agentctl-android/releases/tag/0.12.1).

## 0.12.0 (2026-10-31)

The runtime command `wait` blocks until the app matches or changes, so neither a scenario nor a bridge client polls
(CONTRACT.md version 5): headlessly it moves the virtual clock one timer at a time and stops at the match, in the
running app it waits in real time, and `app run` allows a script its waits plus a minute. Released with agentctl-ios
0.12.0. [Release notes](https://github.com/olbartek/agentctl-android/releases/tag/0.12.0).

### Moving a host to 0.12

0.12 adds the runtime command `wait [<duration>] [k=v …]` (CONTRACT.md version 5, §2.5): it waits until the pairs
match or, with none, until the screen changes, moving the clock headlessly and in real time in the running app. A
scenario that waited in a shell loop, or a bridge client that polled `GET /snapshot`, uses it instead. No breaking
API changes, but four things a host may see:

- **`wait` is now a runtime command,** and a runtime command takes precedence over a screen's: an app command named
  `wait` no longer runs. Rename it.
- **The unknown-command message lists it:** `Valid here: …, expect, advance, mock, wait, device`. A scenario or a
  test that compares that line changes with it.
- **The command docs and the coverage check count it.** Regenerate the command docs with `docs` (they gain a `wait`
  row), and use `wait` in at least one scenario, or the coverage check ("every command is used by a scenario") fails
  with `wait` unused.
- **A host that builds its own headless `RunnerEnvironment`** with the three-argument constructor gets a `wait` that
  waits in real time and never moves its virtual clock; pass `timeUntilNextSleep` (as `HeadlessHost.makeRunner`
  does) for one that moves it.

## 0.11.0 (2026-10-31)

Released together with agentctl-ios 0.11.0, which gives iOS 16 and UIKit apps the bridge launch through its new
`AgentLaunchSession`. There is no Android API change: `AgentLaunch` already starts the bridge in any Android app, with
Views or Compose, from minSdk 28, and `AgentLaunchOptions` was already its own type. The docs map the two ports'
launch types ([the platform differences](platform-differences.md)), the bridge guide says that `AgentLaunch` starts
the same way in a Views app and a Compose app, and [Before 1.0](before-1.0.md) lists what is still to be settled.
Scripts, scenarios, step output and CONTRACT.md (version 4) are as in 0.9. [Release notes](https://github.com/olbartek/agentctl-android/releases/tag/0.11.0).

### Moving a host to 0.11

Nothing changes for an Android host: bump the version. agentctl-ios 0.11 gave iOS 16 and UIKit apps the bridge launch
(its `AgentLaunchSession`), which `AgentLaunch` already is here, in a Views app or a Compose one. Scripts, scenarios,
step output and CONTRACT.md are as in 0.9.

## 0.10.0 (2026-10-31)

Released together with agentctl-ios 0.10.0, which lowers the iOS floor to iOS 16. There is no Android API change and
the library still needs minSdk 28: this release only bumps the version, so both ports keep the same MAJOR.MINOR.
Scripts, scenarios, step output and CONTRACT.md (version 4) are as in 0.9. [Release notes](https://github.com/olbartek/agentctl-android/releases/tag/0.10.0).

### Moving a host to 0.10

Nothing changes for an Android host: bump the version.

## 0.9.0 (2026-10-16)

The debug bridge answers only whoever launched the app, as CONTRACT.md version 4 specifies. `app launch` makes a
fresh 128-bit token per launch, passes it as the `agent-token` extra, records it in `.appctl/bridge.json` (mode
0600) and sends it as `X-Appctl-Token` with every request; an app launched without a well-formed token starts no
bridge and ignores every launch extra. The bridge refuses a malformed request or a body over 1 MiB (400), a foreign
`Host` or any `Origin` (403) and a missing or wrong token (401), and closes cleanly after every answer.
`BridgeServer` takes the token it checks; its tokenless constructors from 0.8 are deprecated until 1.0. Scripts,
scenarios and step output are unchanged. Released together with agentctl-ios 0.9.0, after both ports were checked on
the two NZOZ apps side by side. [Release notes](https://github.com/olbartek/agentctl-android/releases/tag/0.9.0).

### Moving a host to 0.9

0.9 binds the debug bridge's requests to whoever launched the app, with a token per launch. A host's types and
scripts are unchanged; what it changes:

- **A 0.9 app needs a 0.9 `app launch`.** The app starts its bridge only when the launch passes a well-formed
  `agent-token` extra (32 lowercase hex characters), and it ignores every other launch extra without one (`agent-port`,
  `appctl-seed`, `mock-latency`, `clear-session`). A 0.8 CLI sends no token, so a 0.9 app it launches has no bridge
  and `app launch` times out. Update both together.
- **Gate `AgentLaunch` on `AgentLaunchArguments.isRequested(intent)`**, which now means "a well-formed token is
  present" (`AgentLaunch.isRequested` and the JVM's `AgentLaunchOptions.isRequested(arguments)` likewise). A launch by
  hand then builds the plain app, as the release build does, instead of a live store with no bridge
  ([the bridge guide](bridge.md) shows the debug `AppStore`).
- **Test runners that launch the app themselves pass a token**: `intent.putExtra(AgentLaunchArguments.TOKEN,
  AgentLaunchArguments.makeToken())`, a new one per launch, sent as `X-Appctl-Token` with any request they make to
  the bridge. An instrumented test of the debug build has `agentctl-bridge` on its classpath already.
- **Every request carries the token** in `X-Appctl-Token`; the CLI sends it from `.appctl/bridge.json` (now mode
  0600, with a `token` key) or from `APPCTL_TOKEN`. A script or tool of your own that calls the bridge with `curl`
  sends it too: `curl -H "X-Appctl-Token: $(jq -r .token .appctl/bridge.json)" http://127.0.0.1:<port>/snapshot`.
  Without it the bridge answers `401 unauthorized`; a request naming another `Host`, or with an `Origin`, gets
  `403 forbidden`; a body over 1 MiB gets `400 bad request`.
- `BridgeRequest` has a `headers` map, and `BridgeServer` a constructor that takes the expected token. 0.8's
  constructors are deprecated (removed in 1.0): code built against them still links, and a `BridgeServer` built with
  them checks no token. Pass the token instead.

## 0.8.0 (2026-10-16)

The result of a joint audit of both ports: scripts and exit codes follow CONTRACT.md version 3 exactly, and the debug
bridge is harder to stall. New: `help [<subcommand>…]`, a run cancelled when its client goes away,
`AgentNumbers.int(text)` and `AgentText` (canonical equivalence, as Swift's `==`). Scripts now end a line at `\r\n`,
must be UTF-8, and unquote as the reference does; `snapshots` and `check` exit 2 or 3 for usage and environment
errors. The bridge no longer blocks on an idle connection, keeps its header limit, and isolates a handler that throws.
`MockCallLog.all` and `since(index)` are now `entries` and `entries(since:)`. Released together with agentctl-ios
0.8.0. [Release notes](https://github.com/olbartek/agentctl-android/releases/tag/0.8.0).

### Moving a host to 0.8

0.8 changes no host type. What a host may notice:

- `MockCallLog.all` and `since(index)` are now `entries` and `entries(since:)`, as the reference names them; the old
  names still compile, with a deprecation warning (an error where warnings are).
- Parse a number from a command's argument or a link with `AgentNumbers.int(text)`, not `toIntOrNull()`, which also
  reads non-ASCII digits the reference refuses.
- Scripts: a CRLF file now runs line by line, so a CRLF scenario that passed having run nothing may now fail for
  real; `expect "a=b"` (the only `=` quoted) is a usage error; `"a"b"c"` is no
  longer unquoted; text matches by canonical equivalence; a scenario that is not UTF-8 cannot be read (exit 3).
- The CLI: `snapshots` and `check` exit 2 or 3 for usage and environment errors (CONTRACT.md §5); an unusable
  `--session` file is one error line, exit 3; `help [<subcommand>]` prints help.
- The bridge cancels a run whose client went away; a `ScriptRunner.run` cancelled that way throws
  `CancellationException` before its next line, as any suspend call does: its signature is unchanged (the
  reference's `run` now `throws(CancellationError)`).

## 0.7.0 (2026-10-16)

The engine drives any architecture, not only a store: it talks to an `AgentDriver`, and two ship, `AgentStoreDriver`
for a store and `AgentViewModelDriver` for MVVM (view models under an `AgentCoordinator`, work started through
`AgentTasks`). `AgentIntent` and `AgentScreenIntent` describe screens without a reducer, TinyMVVM rebuilds TinyApp as
MVVM with byte-identical output, and every new public top-level name starts with `Agent`. A store host's types and
names change; CONTRACT.md, the scenario files and the step output do not. Released together with agentctl-ios 0.7.0. [Release notes](https://github.com/olbartek/agentctl-android/releases/tag/0.7.0).

### Moving a host to 0.7

0.7 put the engine behind `AgentDriver`. A store host's scripts, scenarios and output are unchanged; its Kotlin
changes as follows:

- Every public name 0.7 adds starts with `Agent`, so none clashes with an app's own.
- `AppCtlConfig<State, Action>`, `HeadlessHost<State, Action>` and `LiveHost<State, Action>` become
  `…<AgentStoreDriver<State, Action>, Action>`, and `ScriptRunner<State, Action>` becomes `ScriptRunner<Action>`. The
  `HeadlessHost(container, mockMethods, …)` and `LiveHost(container, …)` calls are unchanged.
- The test helpers: `AgentCoverage<Action>` and `AgentScenarioChecks<AgentStoreDriver<State, Action>, Action>`. A
  test that reads `host.store` imports `io.github.olbartek.agentctl.runtime.store`.
- `AgentLaunch.store` and `stores` are now `launch.app` and `apps`, and `AgentLaunch.isRequested(intent)` is also
  `AgentLaunchArguments.isRequested(intent)`. That includes the host's own call sites, such as the debug-only class
  that hands `launch.store` to the app's root: it reads `launch.app`. The old names still compile, with a deprecation
  warning, so a build that treats warnings as errors fails until they move.
- A host that built a `ScriptRunner` itself passes `driver = host.driver` instead of the store and the container.
- The store lives on the driver: `host.store` (the import above), `host.driver.state`, and `host.container` is
  `host.driver.container`. `ScriptRunner.store` and `ScriptRunner.state` are gone, since a runner names only the
  action type; keep the host and read `host.store`.
- `host.environment` is now the host's own, which outlives relaunches; the current launch's (whose scope a relaunch
  cancels, the old meaning) is `host.driver.environment`. Work started on `host.environment.scope` is no longer ended
  by a relaunch.
- `LiveHost.onRelaunch: (AgentStore) -> Unit` is `host.driver.onRelaunch: () -> Unit`; read the new store from
  `host.store`.
- Other type parameters: `AgentLaunch<State, Action>` is `AgentLaunch<AgentStoreDriver<State, Action>, Action,
  AgentStore<State, Action>>`, `AgentLaunchSession<Driver, Action>`, `BridgeRouter<Action>`, and `applySeed` and
  `ScenarioRunner.run` are generic over the action only.

## 0.6.0 (2026-10-16)

The device environment: one runtime command, `device`, drives permissions and system prompts, links, pushes, the
app's lifecycle, the network, location and biometry, simulated in the app's process (`AgentEnvironment.device`) and
the same headlessly and through the bridge. Each capability is opt-in through `AppCtlConfig.deviceConfig`, so an app
that enables none prints what 0.5 printed; `AgentAppFactory` builds the clients once per host and a store per launch.
`app permission`, `app openurl`, `app location` and `app appearance` drive the real OS through `adb`. The README was
split into the guides under `docs/guides/`. CONTRACT.md is version 2. No source changes are needed to move to it.
Released together with agentctl-ios 0.6.0. [Release notes](https://github.com/olbartek/agentctl-android/releases/tag/0.6.0).

## 0.5.1 (2026-10-01)

An Android-only patch on 0.5.0: an AVD that agentctl boots (`app launch`, `app test`, `check --ui` with
`--device <AVD>`) no longer hangs after an emulator crash, because the boot skips the emulator's crash-report prompt.
The contract, CLI and scenario files are as in 0.5.0; agentctl-ios stayed at 0.5.0. [Release notes](https://github.com/olbartek/agentctl-android/releases/tag/0.5.1).

## 0.5.0 (2026-09-30)

`app launch` picks a free port, records the launch in `.appctl/bridge.json`, and every bridge response names its app
and platform, so two apps never need a port by hand and a CLI never drives another app. New device commands
(`app screenshot`, `app record`, `app statusbar`, `app info`), `AgentScenarioChecks` in `agentctl-test-support`,
`AgentCommand.choice`, a root `back` fallback and inherited container commands. Live settling waits for Compose to be
idle (a 10 s ceiling), headless time follows `advance`, and no command can hang the CLI. An app built before 0.5.0
sends no platform header, so rebuild it. Released together with agentctl-ios 0.5.0. [Release notes](https://github.com/olbartek/agentctl-android/releases/tag/0.5.0).

## 0.4.2 (2026-09-29)

An Android-only patch on 0.4.1: `AdvanceableClock` counts a sleep only once `advance` can see it, which also deflakes
its timer test. agentctl-ios stayed at 0.4.1, since its side of the fix changed only tests. From this release the
two ports share MAJOR.MINOR and keep their own PATCH.

## 0.4.1 (2026-09-29)

The first release in lockstep with agentctl-ios, at its version number: tags become bare `X.Y.Z` and the JitPack
coordinates follow. TinyApp's scenarios are agentctl-ios's byte for byte again. No library change from v0.2.0.

## v0.2.0 (2026-09-29)

The port of agentctl-ios 0.4: AgentShop (its logic, `shopctl`, its scenarios, a Compose app with the bridge in debug
builds only, UI tests generated from the scenarios, and the benchmark), `app test` running scenario files in the app
on a device, `advance` in the running app on a real-time clock it moves forward, L3 finding a module's Roborazzi or
Paparazzi tasks, and a release build that leaves AgentCtl's runtime, CLI and test support out.

## v0.1.1 (2026-09-25)

A fix in `agentctl-core`: a nested scope's cancel reaches its own effects (`cancellationMatches`), so a sheet inside
a pushed screen no longer keeps its effects after it is dismissed.

## v0.1.0 (2026-09-24)

The first release: the Kotlin port of agentctl-ios 0.3, implementing CONTRACT.md version 1, with every example in it
reproduced byte for byte. The five modules (`agentctl-core`, `agentctl-runtime`, `agentctl-cli`, `agentctl-bridge`,
`agentctl-test-support`), TinyApp with its `tinyctl` CLI, and TinyApp as an Android app with the bridge.
